package com.autoinvoice.api.template;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.template.TemplateSafetyValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TemplateController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TemplateSafetyValidator validator;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public TemplateController(JdbcClient jdbc, ObjectMapper objectMapper, TemplateSafetyValidator validator,
                              IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/invoice-templates")
    @PreAuthorize("hasAnyAuthority('template.publish','preview.generate','invoice.finalize')")
    public List<TemplateResponse> list(Authentication authentication) {
        return jdbc.sql("SELECT * FROM invoice_templates WHERE tenant_id = :tenantId ORDER BY template_code")
                .param("tenantId", user(authentication).tenantId()).query(this::mapTemplate).list();
    }

    @GetMapping("/invoice-templates/{id}")
    @PreAuthorize("hasAnyAuthority('template.publish','preview.generate','invoice.finalize')")
    public ResponseEntity<TemplateDetail> get(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        TemplateResponse template = findTemplate(tenantId, id);
        List<TemplateVersionResponse> versions = jdbc.sql("""
                        SELECT * FROM invoice_template_versions
                        WHERE tenant_id = :tenantId AND template_id = :templateId ORDER BY version_no DESC
                        """)
                .param("tenantId", tenantId).param("templateId", id).query(this::mapVersion).list();
        return ResponseEntity.ok().eTag(VersionEtag.format(template.version()))
                .body(new TemplateDetail(template, versions));
    }

    @PostMapping("/invoice-templates")
    @PreAuthorize("hasAuthority('template.publish')")
    public ResponseEntity<TemplateResponse> create(Authentication authentication,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @Valid @RequestBody TemplateCreateRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/invoice-templates", request,
                TemplateResponse.class, () -> {
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO invoice_templates(
                                        id, tenant_id, template_code, template_name, template_type,
                                        default_language, created_by
                                    ) VALUES (:id, :tenantId, :code, :name, 'HTML', :language, :actorId)
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("code", request.templateCode())
                            .param("name", request.templateName()).param("language", request.defaultLanguage())
                            .param("actorId", actor.userId()).update();
                    TemplateResponse created = findTemplate(actor.tenantId(), id);
                    record(actor, "template.created", "invoice_template", id, null, created,
                            request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PostMapping("/invoice-templates/{id}/copy")
    @PreAuthorize("hasAuthority('template.publish')")
    public ResponseEntity<TemplateResponse> copy(Authentication authentication, @PathVariable UUID id,
                                                 @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                 @Valid @RequestBody TemplateCopyRequest request,
                                                 HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/invoice-templates/" + id + "/copy";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                TemplateResponse.class, () -> {
            TemplateResponse source = findTemplate(actor.tenantId(), id);
            if (source.currentVersionId() == null) {
                throw new DomainException("TEMPLATE_VERSION_REQUIRED", "Only a published template can be copied", 409,
                        Map.of("template_id", id));
            }
            TemplateVersionResponse sourceVersion = findVersion(actor.tenantId(), source.currentVersionId());
            UUID copyId = UuidV7.generate();
            UUID versionId = UuidV7.generate();
            jdbc.sql("""
                            INSERT INTO invoice_templates(
                                id, tenant_id, template_code, template_name, template_type,
                                default_language, status, created_by
                            ) VALUES (:id, :tenantId, :code, :name, 'HTML', :language, 'DRAFT', :actorId)
                            """)
                    .param("id", copyId).param("tenantId", actor.tenantId()).param("code", request.templateCode())
                    .param("name", request.templateName()).param("language", source.defaultLanguage())
                    .param("actorId", actor.userId()).update();
            jdbc.sql("""
                            INSERT INTO invoice_template_versions(
                                id, tenant_id, template_id, version_no, html_content, css_content,
                                schema_json, field_config_json, list_config_json, content_sha256,
                                change_note, status, created_by
                            ) VALUES (
                                :id, :tenantId, :templateId, 1, :html, :css,
                                CAST(:schema AS jsonb), CAST(:fields AS jsonb), CAST(:lists AS jsonb),
                                :hash, :note, 'DRAFT', :actorId
                            )
                            """)
                    .param("id", versionId).param("tenantId", actor.tenantId()).param("templateId", copyId)
                    .param("html", sourceVersion.htmlContent()).param("css", sourceVersion.cssContent())
                    .param("schema", sourceVersion.schema().toString()).param("fields", sourceVersion.fieldConfig().toString())
                    .param("lists", sourceVersion.listConfig().toString()).param("hash", sourceVersion.contentSha256())
                    .param("note", "Copied from " + source.templateCode()).param("actorId", actor.userId()).update();
            TemplateResponse created = findTemplate(actor.tenantId(), copyId);
            record(actor, "template.copied", "invoice_template", copyId, source, created,
                    request.reason(), servletRequest);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        });
    }

    @PostMapping("/invoice-templates/{templateId}/versions")
    @PreAuthorize("hasAuthority('template.publish')")
    public ResponseEntity<TemplateVersionResponse> createVersion(Authentication authentication,
                                                                 @PathVariable UUID templateId,
                                                                 @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                                 @Valid @RequestBody TemplateVersionCreateRequest request,
                                                                 HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/invoice-templates/" + templateId + "/versions";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                TemplateVersionResponse.class, () -> createVersion(actor, templateId, request, servletRequest));
    }

    @Transactional
    protected ResponseEntity<TemplateVersionResponse> createVersion(AuthenticatedUser actor, UUID templateId,
                                                                    TemplateVersionCreateRequest request,
                                                                    HttpServletRequest servletRequest) {
        jdbc.sql("SELECT id FROM invoice_templates WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", actor.tenantId()).param("id", templateId).query(UUID.class).optional()
                .orElseThrow(() -> notFound("template_id", templateId));
        validator.validate(request.htmlContent(), request.cssContent());
        int versionNo = jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM invoice_template_versions WHERE template_id = :templateId")
                .param("templateId", templateId).query(Integer.class).single();
        UUID id = UuidV7.generate();
        String hash = contentHash(request);
        jdbc.sql("""
                        INSERT INTO invoice_template_versions(
                            id, tenant_id, template_id, version_no, html_content, css_content,
                            schema_json, field_config_json, list_config_json, content_sha256,
                            change_note, created_by
                        ) VALUES (
                            :id, :tenantId, :templateId, :versionNo, :html, :css,
                            CAST(:schema AS jsonb), CAST(:fields AS jsonb), CAST(:lists AS jsonb),
                            :hash, :note, :actorId
                        )
                        """)
                .param("id", id).param("tenantId", actor.tenantId()).param("templateId", templateId)
                .param("versionNo", versionNo).param("html", request.htmlContent()).param("css", request.cssContent())
                .param("schema", jsonText(request.schema())).param("fields", jsonText(request.fieldConfig()))
                .param("lists", jsonArrayText(request.listConfig())).param("hash", hash)
                .param("note", request.changeNote()).param("actorId", actor.userId()).update();
        TemplateVersionResponse created = findVersion(actor.tenantId(), id);
        record(actor, "template.version.created", "invoice_template_version", id, null, created,
                request.reason(), servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/template-versions/{id}/validate")
    @PreAuthorize("hasAuthority('template.publish')")
    public ValidationResponse validate(Authentication authentication, @PathVariable UUID id) {
        TemplateVersionResponse version = findVersion(user(authentication).tenantId(), id);
        validator.validate(version.htmlContent(), version.cssContent());
        return new ValidationResponse(true, version.contentSha256());
    }

    @PostMapping("/template-versions/{id}/publish")
    @PreAuthorize("hasAuthority('template.publish')")
    public ResponseEntity<TemplateVersionResponse> publish(Authentication authentication, @PathVariable UUID id,
                                                           @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                           @Valid @RequestBody ReasonRequest request,
                                                           HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/template-versions/" + id + "/publish";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                TemplateVersionResponse.class, () -> publish(actor, id, request.reason(), servletRequest));
    }

    @Transactional
    protected ResponseEntity<TemplateVersionResponse> publish(AuthenticatedUser actor, UUID id, String reason,
                                                              HttpServletRequest servletRequest) {
        TemplateVersionResponse before = jdbc.sql("""
                        SELECT * FROM invoice_template_versions
                        WHERE tenant_id = :tenantId AND id = :id FOR UPDATE
                        """)
                .param("tenantId", actor.tenantId()).param("id", id).query(this::mapVersion).optional()
                .orElseThrow(() -> notFound("template_version_id", id));
        validator.validate(before.htmlContent(), before.cssContent());
        if (!List.of("DRAFT", "PUBLISHED").contains(before.status())) {
            throw new DomainException("INVALID_TEMPLATE_VERSION_STATUS", "Retired template versions cannot be published", 409,
                    Map.of("status", before.status()));
        }
        if ("DRAFT".equals(before.status())) {
            lockTemplateAssetFiles(actor.tenantId(), id);
            jdbc.sql("""
                            UPDATE invoice_template_versions
                            SET status = 'PUBLISHED', published_at = now()
                            WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("tenantId", actor.tenantId()).param("id", id).update();
        }
        jdbc.sql("""
                        UPDATE invoice_templates SET current_version_id = :versionId, status = 'ACTIVE',
                            updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :templateId
                        """)
                .param("versionId", id).param("tenantId", actor.tenantId()).param("templateId", before.templateId()).update();
        TemplateVersionResponse after = findVersion(actor.tenantId(), id);
        record(actor, "template.version.published", "invoice_template_version", id, before, after,
                reason, servletRequest);
        return ResponseEntity.ok(after);
    }

    private void lockTemplateAssetFiles(UUID tenantId, UUID versionId) {
        jdbc.sql("""
                        SELECT file.id
                        FROM invoice_template_assets asset
                        JOIN files file ON file.tenant_id = asset.tenant_id AND file.id = asset.file_id
                        WHERE asset.tenant_id = :tenantId AND asset.template_version_id = :versionId
                        ORDER BY file.id
                        FOR UPDATE OF file
                        """)
                .param("tenantId", tenantId).param("versionId", versionId)
                .query(UUID.class).list();
    }

    @PostMapping("/invoice-templates/{id}/rollback")
    @PreAuthorize("hasAuthority('template.publish')")
    public ResponseEntity<TemplateResponse> rollback(Authentication authentication, @PathVariable UUID id,
                                                     @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                     @Valid @RequestBody TemplateRollbackRequest request,
                                                     HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/invoice-templates/" + id + "/rollback";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                TemplateResponse.class, () -> {
            TemplateResponse before = findTemplate(actor.tenantId(), id);
            TemplateVersionResponse version = findVersion(actor.tenantId(), request.versionId());
            if (!version.templateId().equals(id) || !"PUBLISHED".equals(version.status())) {
                throw new DomainException("TEMPLATE_VERSION_MISMATCH",
                        "Rollback target must be a published version of this template", 422, Map.of());
            }
            jdbc.sql("""
                            UPDATE invoice_templates SET current_version_id = :versionId, status = 'ACTIVE',
                                updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("versionId", version.id()).param("tenantId", actor.tenantId()).param("id", id).update();
            TemplateResponse after = findTemplate(actor.tenantId(), id);
            record(actor, "template.rolled_back", "invoice_template", id, before, after,
                    request.reason(), servletRequest);
            return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
        });
    }

    private TemplateResponse findTemplate(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM invoice_templates WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapTemplate).optional()
                .orElseThrow(() -> notFound("template_id", id));
    }

    private TemplateVersionResponse findVersion(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM invoice_template_versions WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapVersion).optional()
                .orElseThrow(() -> notFound("template_version_id", id));
    }

    private TemplateResponse mapTemplate(ResultSet rs, int row) throws SQLException {
        return new TemplateResponse(rs.getObject("id", UUID.class), rs.getString("template_code"),
                rs.getString("template_name"), rs.getString("template_type"), rs.getString("default_language"),
                rs.getString("status"), rs.getObject("current_version_id", UUID.class), rs.getLong("version"));
    }

    private TemplateVersionResponse mapVersion(ResultSet rs, int row) throws SQLException {
        return new TemplateVersionResponse(rs.getObject("id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getInt("version_no"), rs.getString("html_content"), rs.getString("css_content"),
                json(rs.getString("schema_json"), false), json(rs.getString("field_config_json"), false),
                json(rs.getString("list_config_json"), true), rs.getString("content_sha256"),
                rs.getString("change_note"), rs.getString("status"),
                rs.getObject("published_at", OffsetDateTime.class));
    }

    private String contentHash(TemplateVersionCreateRequest request) {
        String canonical = request.htmlContent() + "\u0000" + request.cssContent() + "\u0000"
                + jsonText(request.schema()) + "\u0000" + jsonText(request.fieldConfig()) + "\u0000"
                + jsonArrayText(request.listConfig());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode json(String value, boolean array) throws SQLException {
        try {
            return objectMapper.readTree(value == null ? (array ? "[]" : "{}") : value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid template JSON", exception);
        }
    }

    private String jsonText(JsonNode value) {
        return value == null || value.isNull() ? "{}" : value.toString();
    }

    private String jsonArrayText(JsonNode value) {
        return value == null || value.isNull() ? "[]" : value.toString();
    }

    private AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private DomainException notFound(String field, UUID id) {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found", 404, Map.of(field, id));
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id, Object before,
                        Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    public record TemplateCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String templateCode,
            @NotBlank String templateName, @NotBlank String defaultLanguage, @NotBlank String reason) {
    }

    public record TemplateCopyRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String templateCode,
            @NotBlank String templateName, @NotBlank String reason) {
    }

    public record TemplateVersionCreateRequest(@NotBlank String htmlContent, String cssContent,
                                               @NotNull JsonNode schema, JsonNode fieldConfig,
                                               JsonNode listConfig, String changeNote, @NotBlank String reason) {
        public TemplateVersionCreateRequest {
            cssContent = cssContent == null ? "" : cssContent;
        }
    }

    public record TemplateRollbackRequest(@NotNull UUID versionId, @NotBlank String reason) {
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    public record TemplateResponse(UUID id, String templateCode, String templateName, String templateType,
                                   String defaultLanguage, String status, UUID currentVersionId, long version) {
    }

    public record TemplateVersionResponse(UUID id, UUID templateId, int versionNo, String htmlContent,
                                          String cssContent, JsonNode schema, JsonNode fieldConfig,
                                          JsonNode listConfig, String contentSha256, String changeNote,
                                          String status, OffsetDateTime publishedAt) {
    }

    public record TemplateDetail(TemplateResponse template, List<TemplateVersionResponse> versions) {
    }

    public record ValidationResponse(boolean valid, String contentSha256) {
    }
}
