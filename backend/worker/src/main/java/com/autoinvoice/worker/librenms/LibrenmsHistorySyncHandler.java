package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.worker.jobs.JobHandler;
import com.autoinvoice.worker.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class LibrenmsHistorySyncHandler implements JobHandler {
    public static final String TYPE = "LIBRENMS_SYNC_HISTORY";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final LibrenmsClientSupport clients;
    private final LibrenmsJsonSupport jsonSupport;
    private final ObjectStorage objectStorage;

    public LibrenmsHistorySyncHandler(JdbcClient jdbc, ObjectMapper objectMapper,
                                      LibrenmsClientSupport clients, LibrenmsJsonSupport jsonSupport,
                                      ObjectStorage objectStorage) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clients = clients;
        this.jsonSupport = jsonSupport;
        this.objectStorage = objectStorage;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        UUID mappingId = requiredUuid(job.payload(), "mapping_id");
        UUID requestedBy = requiredUuid(job.payload(), "requested_by");
        OffsetDateTime periodStart = requiredDateTime(job.payload(), "period_start");
        OffsetDateTime periodEnd = requiredDateTime(job.payload(), "period_end");
        if (!periodStart.isBefore(periodEnd)) {
            throw new IllegalArgumentException("Usage period must be a non-empty half-open interval");
        }
        Mapping mapping = loadMapping(job.tenantId(), mappingId, periodStart, periodEnd);
        UUID runId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO usage_sync_runs(
                            id, tenant_id, librenms_instance_id, mapping_id, job_id, sync_type,
                            period_start, period_end, status
                        ) VALUES (
                            :id, :tenantId, :instanceId, :mappingId, :jobId, 'PERIOD_HISTORY',
                            :periodStart, :periodEnd, 'RUNNING'
                        )
                        """)
                .param("id", runId).param("tenantId", job.tenantId()).param("instanceId", mapping.instanceId())
                .param("mappingId", mappingId).param("jobId", job.id()).param("periodStart", periodStart)
                .param("periodEnd", periodEnd).update();
        Instant started = Instant.now();
        try {
            LibrenmsClientSupport.Connection connection = clients.connection(job.tenantId(), mapping.instanceId());
            String body = connection.get("/api/v0/bills/" + mapping.billId() + "/history");
            JsonNode root = objectMapper.readTree(body);
            JsonNode history = jsonSupport.exactHistory(root, periodStart, periodEnd,
                    ZoneId.of(connection.instance().timezone()));
            Metrics metrics = metrics(mapping, history);
            String responseHash = sha256(body.getBytes(StandardCharsets.UTF_8));
            String dataHash = sha256((history.toString() + "|" + mapping.id() + "|" + periodStart
                    + "|" + periodEnd + "|" + mapping.direction() + "|" + metrics.convertedUsage())
                    .getBytes(StandardCharsets.UTF_8));
            UUID snapshotId = persistSnapshot(job.tenantId(), mapping, requestedBy, periodStart,
                    periodEnd, metrics, history, dataHash);
            UUID fileId = archiveRaw(job.tenantId(), requestedBy, snapshotId, dataHash,
                    body.getBytes(StandardCharsets.UTF_8), responseHash);
            jdbc.sql("""
                            INSERT INTO usage_snapshot_files(tenant_id, usage_snapshot_id, file_id, file_role)
                            VALUES (:tenantId, :snapshotId, :fileId, 'RAW_RESPONSE') ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", job.tenantId()).param("snapshotId", snapshotId).param("fileId", fileId).update();
            ObjectNode summary = objectMapper.createObjectNode().put("usage_snapshot_id", snapshotId.toString())
                    .put("raw_response_file_id", fileId.toString()).put("data_hash", dataHash);
            completeRun(runId, job.tenantId(), responseHash, started, summary);
            return summary.put("sync_run_id", runId.toString());
        } catch (Exception exception) {
            failRun(runId, job.tenantId(), started, exception);
            throw exception;
        }
    }

    private Metrics metrics(Mapping mapping, JsonNode history) {
        Long rateIn = jsonSupport.optionalLong(history, "rate_95th_in_bps", "rate_95th_in",
                "bill_peak_in", "bill_95th_in");
        Long rateOut = jsonSupport.optionalLong(history, "rate_95th_out_bps", "rate_95th_out",
                "bill_peak_out", "bill_95th_out");
        Long finalRate = jsonSupport.optionalLong(history, "rate_95th_bps", "rate_95th",
                "bill_95th", "bill_peak", "rate_95th_aggregate");
        BigDecimal trafficIn = jsonSupport.optionalDecimal(history, "traffic_in_bytes", "traf_in", "bill_in");
        BigDecimal trafficOut = jsonSupport.optionalDecimal(history, "traffic_out_bytes", "traf_out", "bill_out");
        BigDecimal trafficTotal = jsonSupport.optionalDecimal(history, "traffic_total_bytes", "traf_total", "bill_total");
        if (trafficTotal == null && trafficIn != null && trafficOut != null) {
            trafficTotal = trafficIn.add(trafficOut);
        }
        boolean trafficBilling = "TOTAL_TRAFFIC".equals(mapping.billingType());
        BigDecimal raw;
        if (trafficBilling) {
            raw = selectTraffic(mapping.direction(), trafficIn, trafficOut, trafficTotal);
        } else {
            long selected = selectRate(mapping.direction(), rateIn, rateOut, finalRate);
            raw = BigDecimal.valueOf(selected);
        }
        BigDecimal converted = convert(raw, mapping.unit(), trafficBilling);
        Long historyId = jsonSupport.optionalLong(history, "bill_hist_id", "history_id", "id");
        if (historyId == null) {
            throw invalid("LibreNMS history id is required", mapping.id());
        }
        BigDecimal coverage = jsonSupport.optionalDecimal(history, "sample_coverage", "coverage");
        return new Metrics(historyId, rateIn, rateOut, finalRate, trafficIn, trafficOut, trafficTotal,
                raw, converted, coverage);
    }

    static long selectRate(String direction, Long in, Long out, Long finalRate) {
        return switch (direction) {
            case "INBOUND" -> requiredMetric(in, "inbound 95th");
            case "OUTBOUND" -> requiredMetric(out, "outbound 95th");
            case "MAX" -> Math.max(requiredMetric(in, "inbound 95th"), requiredMetric(out, "outbound 95th"));
            case "AGGREGATE", "LIBRENMS_FINAL" -> requiredMetric(finalRate, "LibreNMS final/aggregate 95th");
            default -> throw new DomainException("LIBRENMS_MAPPING_INVALID", "Unsupported billing direction", 422,
                    Map.of("billing_direction", direction));
        };
    }

    static BigDecimal selectTraffic(String direction, BigDecimal in, BigDecimal out, BigDecimal total) {
        return switch (direction) {
            case "INBOUND" -> requiredMetric(in, "inbound traffic");
            case "OUTBOUND" -> requiredMetric(out, "outbound traffic");
            case "MAX" -> requiredMetric(in, "inbound traffic").max(requiredMetric(out, "outbound traffic"));
            case "AGGREGATE", "LIBRENMS_FINAL" -> requiredMetric(total, "total traffic");
            default -> throw new DomainException("LIBRENMS_MAPPING_INVALID", "Unsupported billing direction", 422,
                    Map.of("billing_direction", direction));
        };
    }

    private BigDecimal convert(BigDecimal raw, String unit, boolean traffic) {
        String normalized = unit == null || unit.isBlank() ? (traffic ? "BYTE" : "BPS") : unit.toUpperCase();
        BigDecimal divisor = switch (normalized) {
            case "BPS", "BYTE", "BYTES" -> BigDecimal.ONE;
            case "KBPS", "KB" -> new BigDecimal("1000");
            case "MBPS", "MB" -> new BigDecimal("1000000");
            case "GBPS", "GB" -> new BigDecimal("1000000000");
            case "TB" -> new BigDecimal("1000000000000");
            case "KIB" -> new BigDecimal("1024");
            case "MIB" -> new BigDecimal("1048576");
            case "GIB" -> new BigDecimal("1073741824");
            case "TIB" -> new BigDecimal("1099511627776");
            default -> throw new DomainException("USAGE_UNIT_UNSUPPORTED", "Unsupported billing usage unit", 422,
                    Map.of("unit", unit));
        };
        return raw.divide(divisor, 12, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private UUID persistSnapshot(UUID tenantId, Mapping mapping, UUID requestedBy,
                                 OffsetDateTime start, OffsetDateTime end, Metrics metrics,
                                 JsonNode history, String dataHash) {
        UUID candidate = UuidV7.generate();
        ObjectNode anomaly = objectMapper.createObjectNode();
        if (metrics.coverage() != null && metrics.coverage().compareTo(new BigDecimal("0.95")) < 0) {
            anomaly.put("code", "LOW_SAMPLE_COVERAGE").put("blocking", true)
                    .put("sample_coverage", metrics.coverage().toPlainString());
        }
        String anomalies = anomaly.isEmpty() ? "[]" : "[" + anomaly + "]";
        jdbc.sql("""
                        INSERT INTO usage_snapshots(
                            id, tenant_id, contract_item_id, mapping_id, librenms_instance_id,
                            librenms_bill_id, bill_hist_id, snapshot_kind, period_start, period_end,
                            rate_95th_in_bps, rate_95th_out_bps, rate_95th_bps,
                            traffic_in_bytes, traffic_out_bytes, traffic_total_bytes, billing_direction,
                            raw_usage, converted_usage, billing_usage, unit, sample_coverage,
                            source_timezone, adapter_version, data_hash, anomaly_json, created_by
                        ) VALUES (
                            :id, :tenantId, :contractItemId, :mappingId, :instanceId,
                            :billId, :historyId, 'PREVIEW', :periodStart, :periodEnd,
                            :rateIn, :rateOut, :finalRate,
                            :trafficIn, :trafficOut, :trafficTotal, :direction,
                            :rawUsage, :convertedUsage, :convertedUsage, :unit, :coverage,
                            :timezone, 'librenms-v0-mvp/1', :dataHash, CAST(:anomalies AS jsonb), :requestedBy
                        ) ON CONFLICT DO NOTHING
                        """)
                .param("id", candidate).param("tenantId", tenantId).param("contractItemId", mapping.contractItemId())
                .param("mappingId", mapping.id()).param("instanceId", mapping.instanceId())
                .param("billId", mapping.billId()).param("historyId", metrics.historyId())
                .param("periodStart", start).param("periodEnd", end).param("rateIn", metrics.rateIn())
                .param("rateOut", metrics.rateOut()).param("finalRate", metrics.finalRate())
                .param("trafficIn", metrics.trafficIn()).param("trafficOut", metrics.trafficOut())
                .param("trafficTotal", metrics.trafficTotal()).param("direction", mapping.direction())
                .param("rawUsage", metrics.rawUsage()).param("convertedUsage", metrics.convertedUsage())
                .param("unit", mapping.unit()).param("coverage", metrics.coverage()).param("timezone", mapping.timezone())
                .param("dataHash", dataHash).param("anomalies", anomalies).param("requestedBy", requestedBy).update();
        return jdbc.sql("""
                        SELECT id FROM usage_snapshots
                        WHERE tenant_id = :tenantId AND contract_item_id = :contractItemId
                          AND period_start = :periodStart AND period_end = :periodEnd
                          AND snapshot_kind = 'PREVIEW' AND data_hash = :dataHash
                        """)
                .param("tenantId", tenantId).param("contractItemId", mapping.contractItemId())
                .param("periodStart", start).param("periodEnd", end).param("dataHash", dataHash)
                .query(UUID.class).single();
    }

    private UUID archiveRaw(UUID tenantId, UUID requestedBy, UUID snapshotId, String dataHash,
                            byte[] bytes, String responseHash) throws Exception {
        String objectKey = tenantId + "/usage/" + snapshotId + "/" + dataHash + "/history.json";
        ObjectStorage.StoredObject stored = objectStorage.put(objectKey, bytes, "application/json");
        UUID candidate = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key, original_filename,
                            mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, :provider, :bucket, :objectKey, 'librenms-history.json',
                            'application/json', :size, :sha256, :createdBy
                        ) ON CONFLICT (tenant_id, bucket_name, object_key) DO NOTHING
                        """)
                .param("id", candidate).param("tenantId", tenantId).param("provider", stored.provider())
                .param("bucket", stored.bucket()).param("objectKey", stored.objectKey()).param("size", bytes.length)
                .param("sha256", responseHash).param("createdBy", requestedBy).update();
        return jdbc.sql("""
                        SELECT id FROM files WHERE tenant_id = :tenantId AND bucket_name = :bucket AND object_key = :objectKey
                        """)
                .param("tenantId", tenantId).param("bucket", stored.bucket()).param("objectKey", stored.objectKey())
                .query(UUID.class).single();
    }

    private Mapping loadMapping(UUID tenantId, UUID mappingId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.sql("""
                        SELECT mapping.id, mapping.librenms_instance_id, mapping.librenms_bill_id,
                               mapping.contract_item_id, mapping.billing_direction,
                               contract_item.billing_type, contract_item.unit, instance.timezone
                        FROM librenms_bill_mappings mapping
                        JOIN contract_items contract_item ON contract_item.tenant_id = mapping.tenant_id
                             AND contract_item.id = mapping.contract_item_id
                        JOIN librenms_instances instance ON instance.tenant_id = mapping.tenant_id
                             AND instance.id = mapping.librenms_instance_id
                        WHERE mapping.tenant_id = :tenantId AND mapping.id = :mappingId
                          AND mapping.status = 'ACTIVE' AND mapping.discovery_status = 'CONFIRMED'
                          AND mapping.effective_from <= :periodStart
                          AND (mapping.effective_to IS NULL OR mapping.effective_to >= :periodEnd)
                        """)
                .param("tenantId", tenantId).param("mappingId", mappingId).param("periodStart", start)
                .param("periodEnd", end).query(this::mapMapping).optional()
                .orElseThrow(() -> new DomainException("LIBRENMS_MAPPING_NOT_EFFECTIVE",
                        "A confirmed active mapping must cover the entire usage period", 422,
                        Map.of("mapping_id", mappingId)));
    }

    private Mapping mapMapping(ResultSet rs, int row) throws SQLException {
        return new Mapping(rs.getObject("id", UUID.class), rs.getObject("librenms_instance_id", UUID.class),
                rs.getLong("librenms_bill_id"), rs.getObject("contract_item_id", UUID.class),
                rs.getString("billing_direction"), rs.getString("billing_type"), rs.getString("unit"),
                rs.getString("timezone"));
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

    private void failRun(UUID runId, UUID tenantId, Instant started, Exception exception) {
        String message = exception.getMessage() == null ? "LibreNMS History synchronization failed" : exception.getMessage();
        if (message.length() > 4000) {
            message = message.substring(0, 4000);
        }
        jdbc.sql("""
                        UPDATE usage_sync_runs SET status = 'FAILED', request_count = 1, duration_ms = :duration,
                            error_code = :code, error_message = :message, completed_at = now()
                        WHERE tenant_id = :tenantId AND id = :runId
                        """)
                .param("duration", Duration.between(started, Instant.now()).toMillis())
                .param("code", exception.getClass().getSimpleName().toUpperCase()).param("message", message)
                .param("tenantId", tenantId).param("runId", runId).update();
    }

    private static long requiredMetric(Long value, String name) {
        if (value == null) {
            throw new DomainException("LIBRENMS_RESPONSE_INVALID", "Required " + name + " is missing", 422, Map.of());
        }
        return value;
    }

    private static BigDecimal requiredMetric(BigDecimal value, String name) {
        if (value == null) {
            throw new DomainException("LIBRENMS_RESPONSE_INVALID", "Required " + name + " is missing", 422, Map.of());
        }
        return value;
    }

    private DomainException invalid(String message, UUID mappingId) {
        return new DomainException("LIBRENMS_RESPONSE_INVALID", message, 422, Map.of("mapping_id", mappingId));
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(payload.path(field).asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(TYPE + " payload requires " + field, exception);
        }
    }

    private OffsetDateTime requiredDateTime(JsonNode payload, String field) {
        try {
            return OffsetDateTime.parse(payload.path(field).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(TYPE + " payload requires RFC 3339 " + field, exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Mapping(UUID id, UUID instanceId, long billId, UUID contractItemId,
                           String direction, String billingType, String unit, String timezone) {
    }

    private record Metrics(long historyId, Long rateIn, Long rateOut, Long finalRate,
                           BigDecimal trafficIn, BigDecimal trafficOut, BigDecimal trafficTotal,
                           BigDecimal rawUsage, BigDecimal convertedUsage, BigDecimal coverage) {
    }
}
