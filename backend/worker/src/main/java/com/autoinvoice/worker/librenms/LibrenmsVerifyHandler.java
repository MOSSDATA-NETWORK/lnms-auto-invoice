package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class LibrenmsVerifyHandler implements JobHandler {
    public static final String TYPE = "LIBRENMS_VERIFY";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final LibrenmsClientSupport clients;

    public LibrenmsVerifyHandler(JdbcClient jdbc, ObjectMapper objectMapper, LibrenmsClientSupport clients) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clients = clients;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        UUID instanceId = parseId(job.payload());
        Instance instance = load(job.tenantId(), instanceId);
        UUID runId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO usage_sync_runs(
                            id, tenant_id, librenms_instance_id, job_id, sync_type, status
                        ) VALUES (:id, :tenantId, :instanceId, :jobId, 'VERIFY', 'RUNNING')
                """)
                .param("id", runId).param("tenantId", job.tenantId()).param("instanceId", instance.id())
                .param("jobId", job.id()).update();
        Instant started = Instant.now();
        try {
            String body = clients.connection(job.tenantId(), instanceId).get("/api/v0/bills");
            JsonNode json = objectMapper.readTree(body);
            if (!json.isObject()) {
                throw new IllegalArgumentException("LibreNMS response is not a JSON object");
            }
            String responseHash = sha256(body);
            int discovered = json.path("bills").isArray() ? json.path("bills").size() : 0;
            long durationMs = Duration.between(started, Instant.now()).toMillis();
            ObjectNode summary = objectMapper.createObjectNode().put("bill_count", discovered);
            jdbc.sql("""
                            UPDATE usage_sync_runs
                            SET status = 'SUCCESS', request_count = 1, response_hash = :hash,
                                duration_ms = :duration, summary_json = CAST(:summary AS jsonb), completed_at = now()
                            WHERE id = :runId AND tenant_id = :tenantId
                            """)
                    .param("hash", responseHash).param("duration", durationMs).param("summary", summary.toString())
                    .param("runId", runId).param("tenantId", job.tenantId()).update();
            jdbc.sql("""
                            UPDATE librenms_instances
                            SET status = 'ACTIVE', last_success_at = now(), consecutive_failures = 0,
                                updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND id = :instanceId
                            """)
                    .param("tenantId", job.tenantId()).param("instanceId", instanceId).update();
            return summary.put("response_hash", responseHash).put("sync_run_id", runId.toString());
        } catch (Exception exception) {
            recordFailure(job.tenantId(), instanceId, runId, started, exception);
            throw exception;
        }
    }

    private void recordFailure(UUID tenantId, UUID instanceId, UUID runId, Instant started, Exception exception) {
        String message = exception.getMessage() == null ? "LibreNMS verification failed" : exception.getMessage();
        jdbc.sql("""
                        UPDATE usage_sync_runs
                        SET status = 'FAILED', request_count = 1, duration_ms = :duration,
                            error_code = :errorCode, error_message = :message, completed_at = now()
                        WHERE id = :runId AND tenant_id = :tenantId
                        """)
                .param("duration", Duration.between(started, Instant.now()).toMillis())
                .param("errorCode", exception.getClass().getSimpleName().toUpperCase())
                .param("message", message.length() > 4000 ? message.substring(0, 4000) : message)
                .param("runId", runId).param("tenantId", tenantId).update();
        jdbc.sql("""
                        UPDATE librenms_instances
                        SET status = 'ERROR', last_failure_at = now(), consecutive_failures = consecutive_failures + 1,
                            updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :instanceId
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).update();
    }

    private Instance load(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        SELECT id
                        FROM librenms_instances WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).query(this::map).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "LibreNMS instance was not found", 404,
                        Map.of("instance_id", id)));
    }

    private Instance map(ResultSet rs, int rowNum) throws SQLException {
        return new Instance(rs.getObject("id", UUID.class));
    }

    private UUID parseId(JsonNode payload) {
        try {
            return UUID.fromString(payload.path("instance_id").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("LIBRENMS_VERIFY payload requires instance_id", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Instance(UUID id) {
    }
}
