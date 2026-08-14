package com.autoinvoice.api.document;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.storage.ApiObjectStorage;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-templates")
public class DocumentTemplateController {
    private static final int MAX_TEMPLATE_BYTES = 8 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final ApiObjectStorage objectStorage;
    private final AuditService audit;

    public DocumentTemplateController(JdbcClient jdbc, ApiObjectStorage objectStorage, AuditService audit) {
        this.jdbc = jdbc;
        this.objectStorage = objectStorage;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('contract.write','preview.generate','template.publish','system.admin')")
    public List<DocumentTemplateResponse> list(Authentication authentication,
                                       @RequestParam(name = "type", required = false) String type) {
        UUID tenantId = principal(authentication).tenantId();
        return jdbc.sql("""
                        SELECT * FROM document_templates
                        WHERE tenant_id = :tenantId
                          AND (CAST(:type AS varchar) IS NULL OR template_type = :type)
                        ORDER BY template_type, template_code
                        """)
                .param("tenantId", tenantId).param("type", blank(type)).query(this::map).list();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('template.publish','system.admin')")
    @Transactional
    public ResponseEntity<DocumentTemplateResponse> create(Authentication authentication,
                                                   @RequestParam("template_code") String code,
                                                   @RequestParam("template_name") String name,
                                                   @RequestParam("template_type") String type,
                                                   @RequestParam(value = "description", required = false) String description,
                                                   @RequestParam("file") MultipartFile multipart,
                                                   @RequestParam(value = "reason", required = false) String reason,
                                                   HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        if (!List.of("CONTRACT_DOCX", "INVOICE_XLSX").contains(type)) {
            throw new DomainException("TEMPLATE_TYPE_INVALID", "Template type must be CONTRACT_DOCX or INVOICE_XLSX", 422, Map.of());
        }
        byte[] bytes = multipart.getBytes();
        boolean docx = "CONTRACT_DOCX".equals(type);
        boolean okExt = docx
                ? multipart.getOriginalFilename().toLowerCase().endsWith(".docx")
                : multipart.getOriginalFilename().toLowerCase().endsWith(".xlsx");
        if (bytes.length > MAX_TEMPLATE_BYTES || !okExt) {
            throw new DomainException("TEMPLATE_INVALID", "Template must be a " + (docx ? "docx" : "xlsx") + " under 8 MiB", 422, Map.of());
        }
        String mime = docx
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        UUID fileId = store(actor, bytes, safeFilename(multipart.getOriginalFilename()), mime);
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO document_templates(
                            id, tenant_id, template_code, template_name, template_type, file_id, description,
                            status, created_by
                        ) VALUES (
                            :id, :tenantId, :code, :name, :type, :fileId, :description, 'ACTIVE', :createdBy
                        )
                        """)
                .param("id", id).param("tenantId", actor.tenantId()).param("code", code.trim())
                .param("name", name.trim()).param("type", type).param("fileId", fileId)
                .param("description", blank(description)).param("createdBy", actor.userId()).update();
        DocumentTemplateResponse created = find(actor.tenantId(), id);
        recordAudit(actor, "document_template.created", id, reason, servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('template.publish','system.admin')")
    @Transactional
    public ResponseEntity<DocumentTemplateResponse> update(Authentication authentication, @PathVariable UUID id,
                                                   @RequestHeader(org.springframework.http.HttpHeaders.IF_MATCH) String ifMatch,
                                                   @RequestParam(value = "template_name", required = false) String name,
                                                   @RequestParam(value = "description", required = false) String description,
                                                   @RequestParam(value = "status", required = false) String status,
                                                   @RequestParam(value = "file", required = false) MultipartFile multipart,
                                                   @RequestParam(value = "reason", required = false) String reason,
                                                   HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        DocumentTemplateResponse before = find(actor.tenantId(), id);
        long version = VersionEtag.parse(ifMatch);
        UUID fileId = null;
        if (multipart != null && !multipart.isEmpty()) {
            byte[] bytes = multipart.getBytes();
            boolean docx = "CONTRACT_DOCX".equals(before.templateType());
            boolean okExt = docx
                    ? multipart.getOriginalFilename().toLowerCase().endsWith(".docx")
                    : multipart.getOriginalFilename().toLowerCase().endsWith(".xlsx");
            if (bytes.length > MAX_TEMPLATE_BYTES || !okExt) {
                throw new DomainException("TEMPLATE_INVALID", "Template must be a " + (docx ? "docx" : "xlsx") + " under 8 MiB", 422, Map.of());
            }
            String mime = docx
                    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            fileId = store(actor, bytes, safeFilename(multipart.getOriginalFilename()), mime);
        }
        int changed = jdbc.sql("""
                        UPDATE document_templates SET
                            template_name = COALESCE(:name, template_name),
                            description = COALESCE(:description, description),
                            file_id = COALESCE(:fileId, file_id),
                            status = COALESCE(:status, status), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", blank(name)).param("description", blank(description))
                .param("fileId", fileId).param("status", blank(status))
                .param("tenantId", actor.tenantId()).param("id", id).param("version", version).update();
        if (changed != 1) {
            throw new DomainException("VERSION_CONFLICT", "Document template was modified by another request", 409,
                    Map.of("expected_version", version));
        }
        DocumentTemplateResponse after = find(actor.tenantId(), id);
        recordAudit(actor, "document_template.updated", id, reason, servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('template.publish','system.admin')")
    @Transactional
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID id,
                                       @RequestHeader(org.springframework.http.HttpHeaders.IF_MATCH) String ifMatch,
                                       @RequestParam(value = "reason", required = false) String reason,
                                       HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        DocumentTemplateResponse before = find(actor.tenantId(), id);
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        DELETE FROM document_templates
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("tenantId", actor.tenantId()).param("id", id).param("version", version).update();
        if (changed != 1) {
            throw new DomainException("VERSION_CONFLICT", "Document template was modified by another request", 409,
                    Map.of("expected_version", version));
        }
        recordAudit(actor, "document_template.deleted", id, reason, servletRequest);
        return ResponseEntity.noContent().build();
    }

    private DocumentTemplateResponse find(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM document_templates WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::map).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Document template was not found", 404,
                        Map.of("template_id", id)));
    }

    private UUID store(AuthenticatedUser actor, byte[] bytes, String filename, String mimeType) throws Exception {
        String sha256 = sha256(bytes);
        String objectKey = actor.tenantId() + "/documents/templates/" + actor.userId() + "/" + sha256 + "/" + filename;
        ApiObjectStorage.StoredObject stored = objectStorage.put(objectKey, bytes, mimeType);
        UUID candidate = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, :provider, :bucket, :objectKey,
                            :filename, :mimeType, :size, :sha256, :createdBy
                        ) ON CONFLICT (tenant_id, bucket_name, object_key) DO NOTHING
                        """)
                .param("id", candidate).param("tenantId", actor.tenantId())
                .param("provider", stored.provider()).param("bucket", stored.bucket())
                .param("objectKey", stored.objectKey()).param("filename", filename)
                .param("mimeType", mimeType).param("size", bytes.length).param("sha256", sha256)
                .param("createdBy", actor.userId()).update();
        return jdbc.sql("""
                        SELECT id FROM files
                        WHERE tenant_id = :tenantId AND bucket_name = :bucket AND object_key = :objectKey
                        """)
                .param("tenantId", actor.tenantId()).param("bucket", stored.bucket())
                .param("objectKey", stored.objectKey()).query(UUID.class).single();
    }

    private DocumentTemplateResponse map(ResultSet rs, int row) throws SQLException {
        return new DocumentTemplateResponse(rs.getObject("id", UUID.class), rs.getString("template_code"),
                rs.getString("template_name"), rs.getString("template_type"), rs.getObject("file_id", UUID.class),
                rs.getString("description"), rs.getString("status"), rs.getLong("version"));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String safeFilename(String name) {
        return name == null ? "document" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replace("..", "_");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void recordAudit(AuthenticatedUser actor, String action, UUID id, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action,
                "document_template", id, null, null, reason == null ? "template" : reason,
                request.getHeader("X-Request-Id"));
    }

    public record DocumentTemplateResponse(UUID id, String templateCode, String templateName, String templateType,
                                   UUID fileId, String description, String status, long version) {
    }
}
