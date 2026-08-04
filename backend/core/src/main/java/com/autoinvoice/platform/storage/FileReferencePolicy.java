package com.autoinvoice.platform.storage;

import com.autoinvoice.platform.DomainException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FileReferencePolicy {
    private final JdbcClient jdbc;

    public FileReferencePolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public FileMetadata requireAssociable(UUID tenantId, UUID fileId, UUID actorId,
                                          Set<String> actorPermissions) {
        FileMetadata file = jdbc.sql("""
                        SELECT id, mime_type, created_by
                        FROM files
                        WHERE tenant_id = :tenantId AND id = :fileId AND deleted_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("fileId", fileId)
                .query((rs, row) -> new FileMetadata(
                        rs.getObject("id", UUID.class),
                        rs.getString("mime_type"),
                        rs.getObject("created_by", UUID.class)))
                .optional()
                .orElseThrow(() -> notFound(fileId));
        boolean systemAdministrator = actorPermissions != null && actorPermissions.contains("system.admin");
        if (!systemAdministrator && !Objects.equals(actorId, file.createdBy())) {
            throw notFound(fileId);
        }
        return file;
    }

    private DomainException notFound(UUID fileId) {
        return new DomainException("RESOURCE_NOT_FOUND", "File was not found", 404,
                Map.of("file_id", fileId));
    }

    public record FileMetadata(UUID id, String mimeType, UUID createdBy) {
    }
}
