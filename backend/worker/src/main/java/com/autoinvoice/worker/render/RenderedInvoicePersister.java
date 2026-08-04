package com.autoinvoice.worker.render;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.outbox.OutboxService;
import com.autoinvoice.worker.storage.ObjectStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class RenderedInvoicePersister {
    private final JdbcClient jdbc;
    private final OutboxService outbox;

    public RenderedInvoicePersister(JdbcClient jdbc, OutboxService outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Transactional
    public UUID persist(InvoiceRenderSource source, ObjectStorage.StoredObject storedObject,
                        long size, String sha256, String chromiumVersion) {
        LockedInvoice lockedInvoice = jdbc.sql("""
                        SELECT invoice.document_status, preview.origin_invoice_id
                        FROM invoices invoice
                        JOIN invoice_previews preview
                          ON preview.tenant_id = invoice.tenant_id
                         AND preview.id = invoice.source_preview_id
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                        FOR UPDATE OF invoice
                        """)
                .param("tenantId", source.tenantId()).param("invoiceId", source.invoiceId())
                .query((rs, rowNum) -> new LockedInvoice(
                        rs.getString("document_status"),
                        rs.getObject("origin_invoice_id", UUID.class)))
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice was not found", 404,
                        Map.of("invoice_id", source.invoiceId())));
        String lockedStatus = lockedInvoice.documentStatus();
        if (!("FINALIZING".equals(lockedStatus) || "CONFIRMED".equals(lockedStatus))) {
            throw new DomainException("INVOICE_FINALIZATION_STATE_CHANGED",
                    "Invoice is no longer eligible for PDF confirmation", 409,
                    Map.of("invoice_id", source.invoiceId(), "status", lockedStatus));
        }
        if ("FINALIZING".equals(lockedStatus) && lockedInvoice.originInvoiceId() != null) {
            lockVoidedOrigin(source.tenantId(), lockedInvoice.originInvoiceId());
        }

        UUID candidateFileId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, :provider, :bucket, :objectKey,
                            :filename, 'application/pdf', :size, :sha256, :createdBy
                        )
                        ON CONFLICT (tenant_id, bucket_name, object_key) DO NOTHING
                        """)
                .param("id", candidateFileId)
                .param("tenantId", source.tenantId())
                .param("provider", storedObject.provider())
                .param("bucket", storedObject.bucket())
                .param("objectKey", storedObject.objectKey())
                .param("filename", source.invoiceNumber() + ".pdf")
                .param("size", size)
                .param("sha256", sha256)
                .param("createdBy", source.finalizedBy())
                .update();
        UUID fileId = jdbc.sql("""
                        SELECT id FROM files
                        WHERE tenant_id = :tenantId AND bucket_name = :bucket AND object_key = :objectKey
                        """)
                .param("tenantId", source.tenantId())
                .param("bucket", storedObject.bucket())
                .param("objectKey", storedObject.objectKey())
                .query(UUID.class)
                .single();
        jdbc.sql("SELECT id FROM files WHERE tenant_id = :tenantId AND id = :fileId FOR UPDATE")
                .param("tenantId", source.tenantId()).param("fileId", fileId)
                .query(UUID.class).single();

        jdbc.sql("""
                        INSERT INTO invoice_files(
                            id, tenant_id, invoice_id, file_id, file_role, template_version_id,
                            renderer_version, chromium_version, content_sha256
                        ) VALUES (
                            :id, :tenantId, :invoiceId, :fileId, 'PDF', :templateVersionId,
                            'auto-invoice-worker/0.1.0', :chromiumVersion, :sha256
                        )
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", UuidV7.generate())
                .param("tenantId", source.tenantId())
                .param("invoiceId", source.invoiceId())
                .param("fileId", fileId)
                .param("templateVersionId", source.templateVersionId())
                .param("chromiumVersion", chromiumVersion)
                .param("sha256", sha256)
                .update();

        int updated = jdbc.sql("""
                        UPDATE invoices
                        SET document_status = 'CONFIRMED', confirmed_at = COALESCE(confirmed_at, clock_timestamp()),
                            updated_at = clock_timestamp(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :invoiceId AND document_status = 'FINALIZING'
                        """)
                .param("tenantId", source.tenantId())
                .param("invoiceId", source.invoiceId())
                .update();
        boolean confirmedNow = updated == 1;
        if (!confirmedNow) {
            String status = jdbc.sql("SELECT document_status FROM invoices WHERE tenant_id = :tenantId AND id = :invoiceId")
                    .param("tenantId", source.tenantId())
                    .param("invoiceId", source.invoiceId())
                    .query(String.class)
                    .optional()
                    .orElse("MISSING");
            if (!"CONFIRMED".equals(status)) {
                throw new DomainException("INVOICE_FINALIZATION_STATE_CHANGED",
                        "Invoice is no longer eligible for PDF confirmation", 409,
                        Map.of("invoice_id", source.invoiceId(), "status", status));
            }
        }
        if (confirmedNow && lockedInvoice.originInvoiceId() != null) {
            completeReplacement(source, lockedInvoice.originInvoiceId());
        }
        jdbc.sql("""
                        UPDATE invoice_previews preview
                        SET status = 'FINALIZED', finalized_at = COALESCE(preview.finalized_at, now()),
                            updated_at = now(), version = preview.version + 1
                        FROM invoices invoice
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                          AND preview.tenant_id = invoice.tenant_id AND preview.id = invoice.source_preview_id
                          AND preview.status = 'FINALIZING'
                        """)
                .param("tenantId", source.tenantId())
                .param("invoiceId", source.invoiceId())
                .update();
        if (confirmedNow) {
            outbox.append(source.tenantId(), "invoice", source.invoiceId(), "invoice.confirmed", 1,
                    Map.of("invoice_id", source.invoiceId(), "invoice_number", source.invoiceNumber(),
                            "file_id", fileId, "content_sha256", sha256,
                            "template_version_id", source.templateVersionId()), Map.of());
        }
        return fileId;
    }

    private void lockVoidedOrigin(UUID tenantId, UUID originInvoiceId) {
        String status = jdbc.sql("""
                        SELECT document_status
                        FROM invoices
                        WHERE tenant_id = :tenantId AND id = :invoiceId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .param("invoiceId", originInvoiceId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND",
                        "The correction origin invoice was not found", 404,
                        Map.of("origin_invoice_id", originInvoiceId)));
        if (!"VOIDED".equals(status)) {
            throw new DomainException("ORIGIN_INVOICE_NOT_REPLACEABLE",
                    "The correction origin is no longer a voided invoice", 409,
                    Map.of("origin_invoice_id", originInvoiceId, "status", status));
        }
    }

    private void completeReplacement(InvoiceRenderSource source, UUID originInvoiceId) {
        jdbc.sql("""
                        INSERT INTO invoice_relations(
                            id, tenant_id, source_invoice_id, target_invoice_id,
                            relation_type, reason, created_by
                        ) VALUES (
                            :id, :tenantId, :sourceInvoiceId, :targetInvoiceId,
                            'REPLACES', 'Replacement invoice confirmed from correction preview', :actorId
                        )
                        """)
                .param("id", UuidV7.generate())
                .param("tenantId", source.tenantId())
                .param("sourceInvoiceId", originInvoiceId)
                .param("targetInvoiceId", source.invoiceId())
                .param("actorId", source.finalizedBy())
                .update();
        int replaced = jdbc.sql("""
                        UPDATE invoices
                        SET document_status = 'REPLACED', updated_at = clock_timestamp(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :invoiceId AND document_status = 'VOIDED'
                        """)
                .param("tenantId", source.tenantId())
                .param("invoiceId", originInvoiceId)
                .update();
        if (replaced != 1) {
            throw new DomainException("ORIGIN_INVOICE_NOT_REPLACEABLE",
                    "The correction origin is no longer a voided invoice", 409,
                    Map.of("origin_invoice_id", originInvoiceId));
        }
    }

    private record LockedInvoice(String documentStatus, UUID originInvoiceId) {
    }
}
