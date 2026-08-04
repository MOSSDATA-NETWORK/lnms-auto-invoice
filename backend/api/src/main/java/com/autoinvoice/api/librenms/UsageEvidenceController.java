package com.autoinvoice.api.librenms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class UsageEvidenceController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public UsageEvidenceController(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/usage-snapshots")
    @PreAuthorize("hasAnyAuthority('usage.sync','preview.generate','preview.approve.business')")
    public List<UsageSnapshotResponse> snapshots(Authentication authentication,
                                                 @RequestParam(name = "contract_item_id", required = false) UUID contractItemId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                        SELECT snapshot.* FROM usage_snapshots snapshot
                        WHERE snapshot.tenant_id = :tenantId
                          AND (:contractItemId IS NULL OR snapshot.contract_item_id = :contractItemId)
                        ORDER BY snapshot.created_at DESC LIMIT :limit
                        """)
                .param("tenantId", user(authentication).tenantId()).param("contractItemId", contractItemId)
                .param("limit", Math.max(1, Math.min(limit, 500))).query(this::mapSnapshot).list();
    }

    @GetMapping("/usage-snapshots/{id}")
    @PreAuthorize("hasAnyAuthority('usage.sync','preview.generate','preview.approve.business')")
    public UsageSnapshotDetail snapshot(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        UsageSnapshotResponse snapshot = jdbc.sql("SELECT * FROM usage_snapshots WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapSnapshot).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Usage snapshot was not found", 404,
                        Map.of("usage_snapshot_id", id)));
        List<EvidenceFile> files = jdbc.sql("""
                        SELECT link.file_role, file.id, file.original_filename, file.mime_type,
                               file.file_size, file.sha256, file.created_at
                        FROM usage_snapshot_files link
                        JOIN files file ON file.tenant_id = link.tenant_id AND file.id = link.file_id
                        WHERE link.tenant_id = :tenantId AND link.usage_snapshot_id = :snapshotId
                        ORDER BY link.file_role, file.created_at
                        """)
                .param("tenantId", tenantId).param("snapshotId", id).query((rs, row) -> new EvidenceFile(
                        rs.getString("file_role"), rs.getObject("id", UUID.class), rs.getString("original_filename"),
                        rs.getString("mime_type"), rs.getLong("file_size"), rs.getString("sha256"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
        return new UsageSnapshotDetail(snapshot, files);
    }

    @GetMapping("/usage-sync-runs")
    @PreAuthorize("hasAuthority('usage.sync')")
    public List<SyncRunResponse> runs(Authentication authentication, @RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                        SELECT * FROM usage_sync_runs WHERE tenant_id = :tenantId
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", user(authentication).tenantId()).param("limit", Math.max(1, Math.min(limit, 500)))
                .query((rs, row) -> new SyncRunResponse(rs.getObject("id", UUID.class),
                        rs.getObject("librenms_instance_id", UUID.class), rs.getObject("mapping_id", UUID.class),
                        rs.getString("sync_type"), rs.getObject("period_start", OffsetDateTime.class),
                        rs.getObject("period_end", OffsetDateTime.class), rs.getString("status"),
                        rs.getInt("request_count"), rs.getString("response_hash"), rs.getObject("duration_ms", Long.class),
                        json(rs.getString("summary_json")), rs.getString("error_code"), rs.getString("error_message"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class)))
                .list();
    }

    private UsageSnapshotResponse mapSnapshot(ResultSet rs, int row) throws SQLException {
        return new UsageSnapshotResponse(rs.getObject("id", UUID.class), rs.getObject("contract_item_id", UUID.class),
                rs.getObject("mapping_id", UUID.class), rs.getLong("librenms_bill_id"),
                rs.getObject("bill_hist_id", Long.class), rs.getString("snapshot_kind"),
                rs.getObject("period_start", OffsetDateTime.class), rs.getObject("period_end", OffsetDateTime.class),
                rs.getObject("rate_95th_in_bps", Long.class), rs.getObject("rate_95th_out_bps", Long.class),
                rs.getObject("rate_95th_bps", Long.class), rs.getBigDecimal("traffic_in_bytes"),
                rs.getBigDecimal("traffic_out_bytes"), rs.getBigDecimal("traffic_total_bytes"),
                rs.getString("billing_direction"), rs.getBigDecimal("raw_usage"),
                rs.getBigDecimal("converted_usage"), rs.getBigDecimal("rounded_usage"),
                rs.getBigDecimal("billing_usage"), rs.getString("unit"), rs.getBigDecimal("sample_coverage"),
                rs.getString("source_timezone"), rs.getString("adapter_version"), rs.getString("data_hash"),
                json(rs.getString("anomaly_json")), rs.getObject("invalidated_at", OffsetDateTime.class),
                rs.getString("invalidation_reason"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted usage JSON is invalid", exception);
        }
    }

    private AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    public record UsageSnapshotResponse(UUID id, UUID contractItemId, UUID mappingId, long librenmsBillId,
                                        Long billHistoryId, String snapshotKind, OffsetDateTime periodStart,
                                        OffsetDateTime periodEnd, Long rate95thInBps, Long rate95thOutBps,
                                        Long rate95thBps, BigDecimal trafficInBytes, BigDecimal trafficOutBytes,
                                        BigDecimal trafficTotalBytes, String billingDirection, BigDecimal rawUsage,
                                        BigDecimal convertedUsage, BigDecimal roundedUsage, BigDecimal billingUsage,
                                        String unit, BigDecimal sampleCoverage, String sourceTimezone,
                                        String adapterVersion, String dataHash, JsonNode anomalies,
                                        OffsetDateTime invalidatedAt, String invalidationReason,
                                        OffsetDateTime createdAt) {
    }

    public record UsageSnapshotDetail(UsageSnapshotResponse snapshot, List<EvidenceFile> files) {
    }

    public record EvidenceFile(String fileRole, UUID fileId, String filename, String mimeType,
                               long fileSize, String sha256, OffsetDateTime createdAt) {
    }

    public record SyncRunResponse(UUID id, UUID librenmsInstanceId, UUID mappingId, String syncType,
                                  OffsetDateTime periodStart, OffsetDateTime periodEnd, String status,
                                  int requestCount, String responseHash, Long durationMs, JsonNode summary,
                                  String errorCode, String errorMessage, OffsetDateTime startedAt,
                                  OffsetDateTime completedAt) {
    }
}
