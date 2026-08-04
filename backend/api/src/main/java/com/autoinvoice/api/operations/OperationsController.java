package com.autoinvoice.api.operations;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations")
@PreAuthorize("hasAuthority('system.admin')")
public class OperationsController {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public OperationsController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/settings")
    public ResponseEntity<OperationalSettings> settings(Authentication authentication) {
        OperationalSettings settings = find(principal(authentication).tenantId());
        return ResponseEntity.ok().eTag(VersionEtag.format(settings.version())).body(settings);
    }

    @PatchMapping("/settings")
    public ResponseEntity<OperationalSettings> update(Authentication authentication,
                                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                       @Valid @RequestBody SettingsRequest request,
                                                       HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        long headerVersion = VersionEtag.parse(ifMatch);
        if (headerVersion != request.expectedVersion()) {
            throw new DomainException("VERSION_CONFLICT",
                    "If-Match and expected_version must identify the same settings version", 409,
                    Map.of("if_match_version", headerVersion, "expected_version", request.expectedVersion()));
        }
        OperationalSettings before = find(actor.tenantId());
        if (before.version() != request.expectedVersion()) {
            throw versionConflict(request.expectedVersion(), before.version());
        }
        if (request.emergencyStop() && (request.emergencyReason() == null || request.emergencyReason().isBlank())) {
            throw new DomainException("EMERGENCY_REASON_REQUIRED",
                    "An emergency stop reason is required", 422, Map.of());
        }
        if (request.autoGenerationEnabled() && request.systemUserId() == null) {
            throw new DomainException("SYSTEM_USER_REQUIRED",
                    "A system user is required before automatic generation can be enabled", 422, Map.of());
        }
        if (request.systemUserId() != null) {
            boolean validUser = jdbc.sql("""
                            SELECT EXISTS(
                                SELECT 1 FROM users
                                WHERE tenant_id = :tenantId AND id = :userId AND status = 'ACTIVE'
                            )
                            """)
                    .param("tenantId", actor.tenantId()).param("userId", request.systemUserId())
                    .query(Boolean.class).single();
            if (!validUser) {
                throw new DomainException("SYSTEM_USER_INVALID",
                        "The automatic task user must be an active user in the current tenant", 422,
                        Map.of("system_user_id", request.systemUserId()));
            }
        }
        int updated = jdbc.sql("""
                        UPDATE tenant_operational_settings
                        SET system_user_id = :systemUserId,
                            auto_generation_enabled = :autoGeneration,
                            auto_send_enabled = :autoSend,
                            emergency_stop = :emergencyStop,
                            emergency_reason = :emergencyReason,
                            updated_by = :updatedBy, updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND version = :expectedVersion
                        """)
                .param("systemUserId", request.systemUserId())
                .param("autoGeneration", request.autoGenerationEnabled())
                .param("autoSend", request.autoSendEnabled())
                .param("emergencyStop", request.emergencyStop())
                .param("emergencyReason", request.emergencyStop() ? request.emergencyReason().trim() : null)
                .param("updatedBy", actor.userId()).param("tenantId", actor.tenantId())
                .param("expectedVersion", request.expectedVersion()).update();
        if (updated != 1) {
            throw versionConflict(request.expectedVersion(), find(actor.tenantId()).version());
        }
        OperationalSettings after = find(actor.tenantId());
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                request.emergencyStop() ? "operations.emergency_stop_enabled" : "operations.settings_updated",
                "tenant_operational_settings", actor.tenantId(), before, after,
                request.emergencyReason() == null ? "" : request.emergencyReason(),
                servletRequest.getHeader("X-Request-Id"));
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @GetMapping("/status")
    public OperationalStatus status(Authentication authentication) {
        UUID tenantId = principal(authentication).tenantId();
        Counts jobs = counts("background_jobs", tenantId,
                "status IN ('PENDING','RETRY','LEASED')", "status = 'DEAD'");
        Counts outbox = counts("outbox_events", tenantId,
                "status IN ('PENDING','RETRY','PUBLISHING')", "status = 'DEAD'");
        Counts notifications = counts("notification_logs", tenantId,
                "status IN ('PENDING','RETRY','SENDING')", "status IN ('FAILED','DEAD')");
        long finalizing = jdbc.sql("""
                        SELECT count(*) FROM invoices
                        WHERE tenant_id = :tenantId AND document_status = 'FINALIZING'
                        """).param("tenantId", tenantId).query(Long.class).single();
        OffsetDateTime oldestPendingJob = jdbc.sql("""
                        SELECT min(created_at) FROM background_jobs
                        WHERE tenant_id = :tenantId AND status IN ('PENDING','RETRY','LEASED')
                        """).param("tenantId", tenantId).query(OffsetDateTime.class).optional().orElse(null);
        return new OperationalStatus(find(tenantId), jobs.pending(), jobs.failed(), outbox.pending(),
                outbox.failed(), notifications.pending(), notifications.failed(), finalizing, oldestPendingJob);
    }

    private Counts counts(String table, UUID tenantId, String pendingPredicate, String failedPredicate) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE %s) AS pending,
                               count(*) FILTER (WHERE %s) AS failed
                        FROM %s WHERE tenant_id = :tenantId
                        """.formatted(pendingPredicate, failedPredicate, table))
                .param("tenantId", tenantId)
                .query((rs, row) -> new Counts(rs.getLong("pending"), rs.getLong("failed"))).single();
    }

    private OperationalSettings find(UUID tenantId) {
        return jdbc.sql("""
                        SELECT * FROM tenant_operational_settings WHERE tenant_id = :tenantId
                        """).param("tenantId", tenantId).query((rs, row) -> new OperationalSettings(
                        rs.getObject("tenant_id", UUID.class), rs.getObject("system_user_id", UUID.class),
                        rs.getBoolean("auto_generation_enabled"), rs.getBoolean("auto_send_enabled"),
                        rs.getBoolean("emergency_stop"), rs.getString("emergency_reason"),
                        rs.getObject("updated_by", UUID.class), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getLong("version"))).optional()
                .orElseThrow(() -> new DomainException("OPERATIONAL_SETTINGS_MISSING",
                        "Tenant operational settings are not initialized", 500, Map.of("tenant_id", tenantId)));
    }

    private DomainException versionConflict(long expected, long current) {
        return new DomainException("VERSION_CONFLICT", "Operational settings were modified", 409,
                Map.of("expected_version", expected, "current_version", current));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    public record SettingsRequest(@PositiveOrZero long expectedVersion, UUID systemUserId,
                                  boolean autoGenerationEnabled, boolean autoSendEnabled,
                                  boolean emergencyStop, String emergencyReason) {
    }

    public record OperationalSettings(UUID tenantId, UUID systemUserId, boolean autoGenerationEnabled,
                                      boolean autoSendEnabled, boolean emergencyStop, String emergencyReason,
                                      UUID updatedBy, OffsetDateTime updatedAt, long version) {
    }

    public record OperationalStatus(OperationalSettings settings, long pendingJobs, long deadJobs,
                                    long pendingOutboxEvents, long deadOutboxEvents,
                                    long pendingNotifications, long failedNotifications,
                                    long finalizingInvoices, OffsetDateTime oldestPendingJobAt) {
    }

    private record Counts(long pending, long failed) {
    }
}
