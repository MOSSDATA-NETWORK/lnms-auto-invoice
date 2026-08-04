package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.worker.jobs.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class LibrenmsDiscoverBillsHandler implements JobHandler {
    public static final String TYPE = "LIBRENMS_DISCOVER_BILLS";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final LibrenmsClientSupport clients;
    private final LibrenmsJsonSupport jsonSupport;

    public LibrenmsDiscoverBillsHandler(JdbcClient jdbc, ObjectMapper objectMapper,
                                        LibrenmsClientSupport clients, LibrenmsJsonSupport jsonSupport) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clients = clients;
        this.jsonSupport = jsonSupport;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        UUID instanceId = requiredUuid(job.payload(), "instance_id");
        UUID runId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO usage_sync_runs(id, tenant_id, librenms_instance_id, job_id, sync_type, status)
                        VALUES (:id, :tenantId, :instanceId, :jobId, 'DISCOVER', 'RUNNING')
                        """)
                .param("id", runId).param("tenantId", job.tenantId()).param("instanceId", instanceId)
                .param("jobId", job.id()).update();
        Instant started = Instant.now();
        try {
            String body = clients.connection(job.tenantId(), instanceId).get("/api/v0/bills");
            JsonNode root = objectMapper.readTree(body);
            List<JsonNode> bills = jsonSupport.bills(root);
            String responseHash = sha256(body.getBytes(StandardCharsets.UTF_8));
            for (JsonNode bill : bills) {
                long billId = jsonSupport.requiredLong(bill, "bill_id", "id");
                jdbc.sql("""
                                INSERT INTO librenms_discovered_bills(
                                    id, tenant_id, librenms_instance_id, librenms_bill_id,
                                    bill_name, bill_ref, bill_custid, bill_type, bill_state,
                                    source_payload_json, response_hash
                                ) VALUES (
                                    :id, :tenantId, :instanceId, :billId,
                                    :billName, :billRef, :billCustid, :billType, :billState,
                                    CAST(:payload AS jsonb), :responseHash
                                )
                                ON CONFLICT (tenant_id, librenms_instance_id, librenms_bill_id)
                                DO UPDATE SET bill_name = EXCLUDED.bill_name, bill_ref = EXCLUDED.bill_ref,
                                    bill_custid = EXCLUDED.bill_custid, bill_type = EXCLUDED.bill_type,
                                    bill_state = EXCLUDED.bill_state, source_payload_json = EXCLUDED.source_payload_json,
                                    response_hash = EXCLUDED.response_hash, last_seen_at = now(),
                                    version = librenms_discovered_bills.version + 1
                                """)
                        .param("id", UuidV7.generate()).param("tenantId", job.tenantId())
                        .param("instanceId", instanceId).param("billId", billId)
                        .param("billName", jsonSupport.optionalText(bill, "bill_name", "name"))
                        .param("billRef", jsonSupport.optionalText(bill, "bill_ref", "ref"))
                        .param("billCustid", jsonSupport.optionalText(bill, "bill_custid", "custid"))
                        .param("billType", jsonSupport.optionalText(bill, "bill_type", "type"))
                        .param("billState", jsonSupport.optionalText(bill, "bill_state", "state"))
                        .param("payload", bill.toString()).param("responseHash", responseHash).update();
            }
            ObjectNode summary = objectMapper.createObjectNode().put("bill_count", bills.size());
            completeRun(runId, job.tenantId(), responseHash, started, summary);
            jdbc.sql("""
                            UPDATE librenms_instances SET status = 'ACTIVE', last_success_at = now(),
                                consecutive_failures = 0, updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND id = :instanceId
                            """)
                    .param("tenantId", job.tenantId()).param("instanceId", instanceId).update();
            return summary.put("sync_run_id", runId.toString()).put("response_hash", responseHash);
        } catch (Exception exception) {
            failRun(runId, job.tenantId(), instanceId, started, exception);
            throw exception;
        }
    }

    private void completeRun(UUID runId, UUID tenantId, String hash, Instant started, ObjectNode summary) {
        jdbc.sql("""
                        UPDATE usage_sync_runs SET status = 'SUCCESS', request_count = 1, response_hash = :hash,
                            duration_ms = :duration, summary_json = CAST(:summary AS jsonb), completed_at = now()
                        WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("hash", hash).param("duration", Duration.between(started, Instant.now()).toMillis())
                .param("summary", summary.toString()).param("tenantId", tenantId).param("runId", runId).update();
    }

    private void failRun(UUID runId, UUID tenantId, UUID instanceId, Instant started, Exception exception) {
        String message = message(exception);
        jdbc.sql("""
                        UPDATE usage_sync_runs SET status = 'FAILED', request_count = 1, duration_ms = :duration,
                            error_code = :code, error_message = :message, completed_at = now()
                        WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("duration", Duration.between(started, Instant.now()).toMillis())
                .param("code", exception.getClass().getSimpleName().toUpperCase()).param("message", message)
                .param("tenantId", tenantId).param("runId", runId).update();
        jdbc.sql("""
                        UPDATE librenms_instances SET status = 'ERROR', last_failure_at = now(),
                            consecutive_failures = consecutive_failures + 1, updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :instanceId
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).update();
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(payload.path(field).asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(TYPE + " payload requires " + field, exception);
        }
    }

    private String message(Exception exception) {
        String value = exception.getMessage() == null ? "LibreNMS discovery failed" : exception.getMessage();
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
