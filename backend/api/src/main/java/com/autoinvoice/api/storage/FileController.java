package com.autoinvoice.api.storage;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private static final long MAX_FILE_SIZE = 25L * 1024 * 1024;
    private static final Set<String> WRITE_PERMISSIONS = Set.of(
            "customer.write", "contract.write", "payment.record", "template.publish", "system.admin");
    private static final Map<FileUse, Set<String>> READ_PERMISSIONS = Map.of(
            FileUse.CONTRACT, Set.of("customer.read", "contract.write", "audit.read"),
            FileUse.TEMPLATE_ASSET, Set.of("template.publish", "preview.generate", "invoice.finalize", "audit.read"),
            FileUse.USAGE_EVIDENCE, Set.of("usage.sync", "preview.generate", "preview.approve.business",
                    "invoice.finalize", "audit.read"),
            FileUse.PREVIEW_ATTACHMENT, Set.of("preview.generate", "preview.adjust", "preview.approve.business",
                    "preview.approve.finance", "invoice.finalize"),
            FileUse.INVOICE_FILE, Set.of("invoice.finalize", "invoice.send", "invoice.void", "payment.record",
                    "audit.read"),
            FileUse.PAYMENT_ATTACHMENT, Set.of("payment.record", "audit.read"),
            FileUse.IMPORT_FILE, Set.of("customer.write", "contract.write"));
    private final JdbcClient jdbc;
    private final ApiObjectStorage objectStorage;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public FileController(JdbcClient jdbc, ApiObjectStorage objectStorage,
                          IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectStorage = objectStorage;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('customer.write','contract.write','payment.record','template.publish','system.admin')")
    public ResponseEntity<FileResponse> upload(Authentication authentication,
                                               @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                               @RequestParam("file") MultipartFile multipart,
                                               @RequestParam(defaultValue = "") String reason,
                                               HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        byte[] bytes = multipart.getBytes();
        String filename = safeFilename(multipart.getOriginalFilename());
        String mimeType = validateFile(filename, multipart.getContentType(), bytes);
        String sha256 = sha256(bytes);
        FileFingerprint fingerprint = new FileFingerprint(filename, mimeType, bytes.length, sha256, reason);
        return idempotency.execute(actor.tenantId(), key, "POST", "/api/v1/files", fingerprint,
                FileResponse.class, () -> {
                    try {
                        String objectKey = actor.tenantId() + "/uploads/" + actor.userId()
                                + "/" + sha256 + "/" + filename;
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
                        FileResponse created = jdbc.sql("""
                                        SELECT * FROM files
                                        WHERE tenant_id = :tenantId AND bucket_name = :bucket AND object_key = :objectKey
                                        """)
                                .param("tenantId", actor.tenantId()).param("bucket", stored.bucket())
                                .param("objectKey", stored.objectKey()).query(this::mapFile).single();
                        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                                "file.uploaded", "file", created.id(), null, created, reason,
                                servletRequest.getHeader("X-Request-Id"));
                        return ResponseEntity.status(HttpStatus.CREATED).eTag('"' + created.sha256() + '"').body(created);
                    } catch (DomainException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        throw new DomainException("OBJECT_STORAGE_UNAVAILABLE", "Unable to store uploaded file", 503,
                                Map.of("cause", exception.getClass().getSimpleName()));
                    }
                });
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<FileResponse> metadata(Authentication authentication, @PathVariable UUID fileId) {
        AuthenticatedUser actor = principal(authentication);
        StoredFile stored = findStored(actor.tenantId(), fileId);
        assertCanRead(actor, stored);
        FileResponse file = stored.response();
        return ResponseEntity.ok().eTag('"' + file.sha256() + '"').body(file);
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<byte[]> download(Authentication authentication, @PathVariable UUID fileId,
                                           HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        StoredFile stored = findStored(actor.tenantId(), fileId);
        assertCanRead(actor, stored);
        byte[] bytes;
        try {
            bytes = objectStorage.get(stored.bucket(), stored.objectKey());
        } catch (Exception exception) {
            throw new DomainException("OBJECT_STORAGE_UNAVAILABLE", "Unable to read stored file", 503,
                    Map.of("file_id", fileId));
        }
        if (bytes.length != stored.size() || !MessageDigest.isEqual(
                sha256(bytes).getBytes(StandardCharsets.US_ASCII), stored.sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new DomainException("FILE_INTEGRITY_FAILED", "Stored file failed its integrity check", 409,
                    Map.of("file_id", fileId));
        }
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), "file.downloaded",
                "file", fileId, null, Map.of("sha256", stored.sha256(), "size", stored.size()), "",
                servletRequest.getHeader("X-Request-Id"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(stored.mimeType()));
        headers.setContentLength(bytes.length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(stored.filename(), StandardCharsets.UTF_8).build());
        headers.setETag('"' + stored.sha256() + '"');
        headers.setCacheControl("private, no-store");
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    private StoredFile findStored(UUID tenantId, UUID fileId) {
        return jdbc.sql("SELECT * FROM files WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL")
                .param("tenantId", tenantId).param("id", fileId)
                .query((rs, row) -> new StoredFile(rs.getObject("id", UUID.class), rs.getString("bucket_name"),
                        rs.getString("object_key"), rs.getString("original_filename"), rs.getString("mime_type"),
                        rs.getLong("file_size"), rs.getString("sha256"),
                        rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class)))
                .optional().orElseThrow(() -> notFound(fileId));
    }

    private void assertCanRead(AuthenticatedUser actor, StoredFile file) {
        Set<FileUse> uses = jdbc.sql("""
                        SELECT 'CONTRACT' AS file_use
                        FROM contract_files WHERE tenant_id = :tenantId AND file_id = :fileId
                        UNION ALL
                        SELECT 'TEMPLATE_ASSET'
                        FROM invoice_template_assets WHERE tenant_id = :tenantId AND file_id = :fileId
                        UNION ALL
                        SELECT 'USAGE_EVIDENCE'
                        FROM usage_snapshot_files WHERE tenant_id = :tenantId AND file_id = :fileId
                        UNION ALL
                        SELECT 'PREVIEW_ATTACHMENT'
                        FROM invoice_preview_adjustments
                        WHERE tenant_id = :tenantId AND attachment_file_id = :fileId
                        UNION ALL
                        SELECT 'INVOICE_FILE'
                        FROM invoice_files WHERE tenant_id = :tenantId AND file_id = :fileId
                        UNION ALL
                        SELECT 'INVOICE_FILE'
                        FROM invoice_adjustments
                        WHERE tenant_id = :tenantId AND attachment_file_id = :fileId
                        UNION ALL
                        SELECT 'PAYMENT_ATTACHMENT'
                        FROM payments WHERE tenant_id = :tenantId AND attachment_file_id = :fileId
                        UNION ALL
                        SELECT 'IMPORT_FILE'
                        FROM import_jobs
                        WHERE tenant_id = :tenantId AND (source_file_id = :fileId OR error_file_id = :fileId)
                        """)
                .param("tenantId", actor.tenantId())
                .param("fileId", file.id())
                .query(String.class)
                .list().stream()
                .map(FileUse::valueOf)
                .collect(Collectors.toUnmodifiableSet());
        if (!isReadAllowed(actor.permissions(), actor.userId(), file.createdBy(), uses)) {
            throw notFound(file.id());
        }
    }

    static boolean isReadAllowed(Set<String> permissions, UUID actorId, UUID createdBy, Set<FileUse> uses) {
        if (permissions.contains("system.admin")) {
            return true;
        }
        if (uses.isEmpty()) {
            return Objects.equals(actorId, createdBy);
        }
        return uses.stream().anyMatch(use -> READ_PERMISSIONS.get(use).stream().anyMatch(permissions::contains));
    }

    private FileResponse mapFile(ResultSet rs, int row) throws SQLException {
        return new FileResponse(rs.getObject("id", UUID.class), rs.getString("original_filename"),
                rs.getString("mime_type"), rs.getLong("file_size"), rs.getString("sha256"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String validateFile(String filename, String suppliedMimeType, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_FILE_SIZE) {
            throw new DomainException("FILE_SIZE_INVALID", "File must contain between 1 byte and 25 MiB", 422,
                    Map.of("file_size", bytes.length));
        }
        String extension = extension(filename);
        return switch (extension) {
            case "csv" -> {
                if (containsNul(bytes)) {
                    throw invalidType(filename);
                }
                yield "text/csv";
            }
            case "xlsx" -> {
                requireMagic(bytes, new int[]{0x50, 0x4b, 0x03, 0x04}, filename);
                yield "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            case "pdf" -> {
                requireMagic(bytes, new int[]{0x25, 0x50, 0x44, 0x46, 0x2d}, filename);
                yield "application/pdf";
            }
            case "png" -> {
                requireMagic(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, filename);
                yield "image/png";
            }
            case "jpg", "jpeg" -> {
                requireMagic(bytes, new int[]{0xff, 0xd8, 0xff}, filename);
                yield "image/jpeg";
            }
            default -> throw invalidType(filename);
        };
    }

    private void requireMagic(byte[] bytes, int[] magic, String filename) {
        if (bytes.length < magic.length) {
            throw invalidType(filename);
        }
        for (int index = 0; index < magic.length; index++) {
            if ((bytes[index] & 0xff) != magic[index]) {
                throw invalidType(filename);
            }
        }
    }

    private boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private DomainException invalidType(String filename) {
        return new DomainException("FILE_TYPE_UNSUPPORTED",
                "Only CSV, XLSX, PDF, PNG and JPEG files with matching content are accepted", 422,
                Map.of("filename", filename));
    }

    private String safeFilename(String original) {
        String value = original == null ? "upload.bin" : original.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        value = value.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (value.isBlank() || value.length() > 180) {
            throw new DomainException("FILE_NAME_INVALID", "File name is empty or too long", 422, Map.of());
        }
        return value;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private DomainException notFound(UUID fileId) {
        return new DomainException("RESOURCE_NOT_FOUND", "File was not found", 404, Map.of("file_id", fileId));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private record FileFingerprint(String filename, String mimeType, long size, String sha256, String reason) {
    }

    enum FileUse {
        CONTRACT,
        TEMPLATE_ASSET,
        USAGE_EVIDENCE,
        PREVIEW_ATTACHMENT,
        INVOICE_FILE,
        PAYMENT_ATTACHMENT,
        IMPORT_FILE
    }

    private record StoredFile(UUID id, String bucket, String objectKey, String filename,
                              String mimeType, long size, String sha256, UUID createdBy,
                              OffsetDateTime createdAt) {
        private FileResponse response() {
            return new FileResponse(id, filename, mimeType, size, sha256, createdAt);
        }
    }

    public record FileResponse(UUID id, String filename, String mimeType, long size,
                               String sha256, OffsetDateTime createdAt) {
    }
}
