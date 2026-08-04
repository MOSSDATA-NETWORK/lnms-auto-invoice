package com.autoinvoice.api.imports;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.autoinvoice.platform.storage.FileReferencePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BackgroundJobService jobs;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;
    private final FileReferencePolicy fileReferencePolicy;

    public ImportController(JdbcClient jdbc, ObjectMapper objectMapper, BackgroundJobService jobs,
                            IdempotencyExecutor idempotency, AuditService audit,
                            FileReferencePolicy fileReferencePolicy) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.audit = audit;
        this.fileReferencePolicy = fileReferencePolicy;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('customer.write','contract.write','system.admin')")
    public List<ImportResponse> list(Authentication authentication,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(name = "import_type", required = false) String importType,
                                     @RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                        SELECT job.*, file.original_filename
                        FROM import_jobs job
                        JOIN files file ON file.tenant_id = job.tenant_id AND file.id = job.source_file_id
                        WHERE job.tenant_id = :tenantId
                          AND (:status IS NULL OR job.status = :status)
                          AND (:importType IS NULL OR job.import_type = :importType)
                        ORDER BY job.created_at DESC LIMIT :limit
                        """)
                .param("tenantId", principal(authentication).tenantId()).param("status", blank(status))
                .param("importType", blank(importType)).param("limit", Math.max(1, Math.min(limit, 200)))
                .query(this::mapImport).list();
    }

    @GetMapping("/{importId}")
    @PreAuthorize("hasAnyAuthority('customer.write','contract.write','system.admin')")
    public ImportDetail get(Authentication authentication, @PathVariable UUID importId) {
        UUID tenantId = principal(authentication).tenantId();
        ImportResponse job = find(tenantId, importId);
        List<ImportErrorResponse> errors = jdbc.sql("""
                        SELECT id, row_number, field_name, error_code, error_message, row_data_json, created_at
                        FROM import_row_errors
                        WHERE tenant_id = :tenantId AND import_job_id = :importId
                        ORDER BY row_number, created_at LIMIT 1000
                        """)
                .param("tenantId", tenantId).param("importId", importId).query(this::mapError).list();
        return new ImportDetail(job, errors);
    }

    @PostMapping("/master-data")
    @PreAuthorize("hasAnyAuthority('customer.write','contract.write','system.admin')")
    public ResponseEntity<ImportAccepted> create(Authentication authentication,
                                                 @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                 @Valid @RequestBody ImportCreateRequest request,
                                                 HttpServletRequest servletRequest) {
        if (key.length() > 200) {
            throw new DomainException("IDEMPOTENCY_KEY_INVALID", "Import idempotency key cannot exceed 200 characters",
                    400, Map.of());
        }
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/imports/master-data", request,
                ImportAccepted.class, () -> {
                    requireImportFile(actor, request.sourceFileId());
                    UUID importId = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO import_jobs(
                                        id, tenant_id, import_type, source_file_id, idempotency_key,
                                        options_json, requested_by
                                    ) VALUES (
                                        :id, :tenantId, :importType, :fileId, :key,
                                        CAST(:options AS jsonb), :requestedBy
                                    )
                                    """)
                            .param("id", importId).param("tenantId", actor.tenantId())
                            .param("importType", request.importType()).param("fileId", request.sourceFileId())
                            .param("key", key).param("options", json(request.options()))
                            .param("requestedBy", actor.userId()).update();
                    UUID jobId = jobs.enqueue(actor.tenantId(), "IMPORT_VALIDATE", "import-validate:" + importId,
                            payload(importId));
                    ImportAccepted accepted = new ImportAccepted(importId, jobId, "UPLOADED");
                    audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                            "import.validation_queued", "import_job", importId, null, accepted,
                            request.reason(), servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.accepted().body(accepted);
                });
    }

    @PostMapping("/{importId}/confirm")
    @PreAuthorize("hasAnyAuthority('customer.write','contract.write','system.admin')")
    public ResponseEntity<ImportAccepted> confirm(Authentication authentication, @PathVariable UUID importId,
                                                  @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                  @Valid @RequestBody ImportConfirmRequest request,
                                                  HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        String path = "/api/v1/imports/" + importId + "/confirm";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                ImportAccepted.class, () -> {
            ImportState state = jdbc.sql("""
                            SELECT status, invalid_rows FROM import_jobs
                            WHERE tenant_id = :tenantId AND id = :id FOR UPDATE
                            """)
                    .param("tenantId", actor.tenantId()).param("id", importId)
                    .query((rs, row) -> new ImportState(rs.getString("status"), rs.getInt("invalid_rows")))
                    .optional().orElseThrow(() -> notFound(importId));
            if (!"READY".equals(state.status()) || state.invalidRows() != 0) {
                throw new DomainException("IMPORT_NOT_READY",
                        "Import must finish validation without invalid rows before confirmation", 409,
                        Map.of("status", state.status(), "invalid_rows", state.invalidRows()));
            }
            jdbc.sql("""
                            UPDATE import_jobs SET status = 'IMPORTING', started_at = COALESCE(started_at, now()),
                                updated_at = now() WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("tenantId", actor.tenantId()).param("id", importId).update();
            UUID jobId = jobs.enqueue(actor.tenantId(), "IMPORT_CONFIRM", "import-confirm:" + importId,
                    payload(importId));
            ImportAccepted accepted = new ImportAccepted(importId, jobId, "IMPORTING");
            audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                    "import.confirmed", "import_job", importId, null, accepted,
                    request.reason(), servletRequest.getHeader("X-Request-Id"));
            return ResponseEntity.accepted().body(accepted);
        });
    }

    @GetMapping("/{importId}/error-file")
    @PreAuthorize("hasAnyAuthority('customer.write','contract.write','system.admin')")
    public ErrorFileResponse errorFile(Authentication authentication, @PathVariable UUID importId) {
        ImportResponse job = find(principal(authentication).tenantId(), importId);
        if (job.errorFileId() == null) {
            throw new DomainException("IMPORT_ERROR_FILE_UNAVAILABLE", "Import has no generated error file", 404,
                    Map.of("import_id", importId));
        }
        return new ErrorFileResponse(job.errorFileId(), "/api/v1/files/" + job.errorFileId() + "/content");
    }

    private ImportResponse find(UUID tenantId, UUID importId) {
        return jdbc.sql("""
                        SELECT job.*, file.original_filename
                        FROM import_jobs job
                        JOIN files file ON file.tenant_id = job.tenant_id AND file.id = job.source_file_id
                        WHERE job.tenant_id = :tenantId AND job.id = :id
                        """)
                .param("tenantId", tenantId).param("id", importId).query(this::mapImport).optional()
                .orElseThrow(() -> notFound(importId));
    }

    private ImportResponse mapImport(ResultSet rs, int row) throws SQLException {
        try {
            return new ImportResponse(rs.getObject("id", UUID.class), rs.getString("import_type"),
                    rs.getObject("source_file_id", UUID.class), rs.getString("original_filename"),
                    rs.getString("status"), rs.getInt("total_rows"), rs.getInt("valid_rows"),
                    rs.getInt("invalid_rows"), rs.getInt("imported_rows"),
                    rs.getObject("error_file_id", UUID.class), objectMapper.readTree(rs.getString("options_json")),
                    rs.getObject("started_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                    rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted import options are invalid", exception);
        }
    }

    private ImportErrorResponse mapError(ResultSet rs, int row) throws SQLException {
        try {
            return new ImportErrorResponse(rs.getObject("id", UUID.class), rs.getInt("row_number"),
                    rs.getString("field_name"), rs.getString("error_code"), rs.getString("error_message"),
                    objectMapper.readTree(rs.getString("row_data_json")),
                    rs.getObject("created_at", OffsetDateTime.class));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted import error row is invalid", exception);
        }
    }

    private void requireImportFile(AuthenticatedUser actor, UUID fileId) {
        String mimeType = fileReferencePolicy.requireAssociable(
                actor.tenantId(), fileId, actor.userId(), actor.permissions()).mimeType();
        if (!List.of("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .contains(mimeType)) {
            throw new DomainException("IMPORT_FILE_TYPE_UNSUPPORTED", "Master data imports require CSV or XLSX", 422,
                    Map.of("mime_type", mimeType));
        }
    }

    private ObjectNode payload(UUID importId) {
        return objectMapper.createObjectNode().put("import_id", importId.toString());
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value == null ? objectMapper.createObjectNode() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Import options are not serializable", exception);
        }
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private DomainException notFound(UUID importId) {
        return new DomainException("RESOURCE_NOT_FOUND", "Import job was not found", 404,
                Map.of("import_id", importId));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    public record ImportCreateRequest(
            @NotBlank @Pattern(regexp = "CUSTOMERS|COMPANIES|SERVICES|CONTRACTS|CONTRACT_ITEMS") String importType,
            @NotNull UUID sourceFileId, JsonNode options, @NotBlank String reason) {
    }

    public record ImportConfirmRequest(@NotBlank String reason) {
    }

    public record ImportAccepted(UUID importId, UUID jobId, String status) {
    }

    public record ImportResponse(UUID id, String importType, UUID sourceFileId, String sourceFilename,
                                 String status, int totalRows, int validRows, int invalidRows, int importedRows,
                                 UUID errorFileId, JsonNode options, OffsetDateTime startedAt,
                                 OffsetDateTime completedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record ImportErrorResponse(UUID id, int rowNumber, String fieldName, String errorCode,
                                      String errorMessage, JsonNode rowData, OffsetDateTime createdAt) {
    }

    public record ImportDetail(ImportResponse job, List<ImportErrorResponse> errors) {
    }

    public record ErrorFileResponse(UUID fileId, String downloadPath) {
    }

    private record ImportState(String status, int invalidRows) {
    }
}
