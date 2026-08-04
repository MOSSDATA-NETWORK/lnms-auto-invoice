package com.autoinvoice.api.jobs;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BackgroundJobService jobService;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public JobController(JdbcClient jdbc, ObjectMapper objectMapper, BackgroundJobService jobService,
                         IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jobService = jobService;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('audit.read','system.admin')")
    public List<JobResponse> list(Authentication authentication,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(defaultValue = "100") int limit) {
        UUID tenantId = ((AuthenticatedUser) authentication.getPrincipal()).tenantId();
        return jdbc.sql("""
                        SELECT * FROM background_jobs
                        WHERE tenant_id = :tenantId
                          AND (:status IS NULL OR status = :status)
                          AND (:type IS NULL OR job_type = :type)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("status", blank(status)).param("type", blank(type))
                .param("limit", Math.max(1, Math.min(limit, 500))).query(this::map).list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('audit.read','system.admin')")
    public JobResponse get(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = ((AuthenticatedUser) authentication.getPrincipal()).tenantId();
        return jdbc.sql("SELECT * FROM background_jobs WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Job was not found", 404,
                        java.util.Map.of("job_id", id)));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('system.admin')")
    public org.springframework.http.ResponseEntity<JobResponse> retry(
            Authentication authentication, @PathVariable UUID id,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody RetryRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = (AuthenticatedUser) authentication.getPrincipal();
        String path = "/api/v1/jobs/" + id + "/retry";
        return idempotency.execute(actor.tenantId(), key, "POST", path, request, JobResponse.class, () -> {
            int updated = jdbc.sql("""
                            UPDATE background_jobs
                            SET status = 'RETRY', available_at = now(), leased_by = NULL, leased_until = NULL,
                                updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :id AND status IN ('DEAD', 'RETRY')
                            """)
                    .param("tenantId", actor.tenantId()).param("id", id).update();
            if (updated != 1) {
                throw new DomainException("JOB_NOT_RETRYABLE", "The job is not in a retryable state", 409,
                        java.util.Map.of("job_id", id));
            }
            JobResponse result = get(authentication, id);
            audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                    "background_job.retried", "background_job", id, null, result,
                    request.reason(), servletRequest.getHeader("X-Request-Id"));
            return org.springframework.http.ResponseEntity.accepted().body(result);
        });
    }

    private JobResponse map(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new JobResponse(
                    rs.getObject("id", UUID.class),
                    rs.getString("job_type"),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getInt("max_attempts"),
                    objectMapper.readTree(rs.getString("payload_json")),
                    rs.getString("result_json") == null ? null : objectMapper.readTree(rs.getString("result_json")),
                    rs.getString("last_error_code"),
                    rs.getString("last_error_message"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("completed_at", OffsetDateTime.class)
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid persisted job JSON", exception);
        }
    }

    public record JobResponse(UUID id, String type, String status, int attemptCount, int maxAttempts,
                              JsonNode payload, JsonNode result, String lastErrorCode, String lastErrorMessage,
                              OffsetDateTime createdAt, OffsetDateTime completedAt) {
    }

    public record RetryRequest(@NotBlank String reason) {
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
