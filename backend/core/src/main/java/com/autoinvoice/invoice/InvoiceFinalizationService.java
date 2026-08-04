package com.autoinvoice.invoice;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.autoinvoice.platform.numbering.NumberSequenceService;
import com.autoinvoice.platform.outbox.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InvoiceFinalizationService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final NumberSequenceService numberSequenceService;
    private final BackgroundJobService jobService;
    private final OutboxService outbox;

    public InvoiceFinalizationService(JdbcClient jdbc, ObjectMapper objectMapper,
                                      NumberSequenceService numberSequenceService,
                                      BackgroundJobService jobService, OutboxService outbox) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.numberSequenceService = numberSequenceService;
        this.jobService = jobService;
        this.outbox = outbox;
    }

    @Transactional
    public FinalizationResult finalizeApprovedPreview(UUID tenantId, UUID previewId, UUID actorId,
                                                      long expectedVersion) {
        ExistingInvoice existing = findExisting(tenantId, previewId);
        if (existing != null) {
            UUID jobId = enqueueRender(tenantId, existing.invoiceId(), existing.dataSnapshotHash());
            return new FinalizationResult(existing.invoiceId(), existing.invoiceNumber(), jobId, existing.status());
        }

        Preview preview = jdbc.sql("""
                        SELECT * FROM invoice_previews
                        WHERE tenant_id = :tenantId AND id = :previewId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId)
                .param("previewId", previewId)
                .query(this::mapPreview)
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice preview was not found", 404,
                        Map.of("preview_id", previewId)));

        existing = findExisting(tenantId, previewId);
        if (existing != null) {
            UUID jobId = enqueueRender(tenantId, existing.invoiceId(), existing.dataSnapshotHash());
            return new FinalizationResult(existing.invoiceId(), existing.invoiceNumber(), jobId, existing.status());
        }
        if (preview.version() != expectedVersion) {
            throw new DomainException("VERSION_CONFLICT", "Invoice preview was modified by another request", 409,
                    Map.of("expected_version", expectedVersion, "current_version", preview.version()));
        }
        if (!"APPROVED".equals(preview.status())) {
            throw new DomainException("PREVIEW_NOT_APPROVED", "Only an approved preview can be finalized", 409,
                    Map.of("preview_id", previewId, "status", preview.status()));
        }

        UUID approvalInstanceId = jdbc.sql("""
                        SELECT id FROM approval_instances
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                          AND status = 'APPROVED' AND preview_version = :previewVersion
                          AND approval_revision = :approvalRevision
                        ORDER BY completed_at DESC NULLS LAST
                        LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("previewId", previewId)
                .param("previewVersion", preview.version())
                .param("approvalRevision", preview.approvalRevision())
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new DomainException("APPROVAL_STALE",
                        "The approval does not match the current preview revision", 409,
                        Map.of("preview_id", previewId)));

        lockFinalizationEvidence(tenantId, previewId);
        lockCorrectionOrigin(tenantId, preview.originInvoiceId());
        List<PreviewItem> items = loadItems(tenantId, previewId);
        List<PreviewAdjustment> adjustments = loadAdjustments(tenantId, previewId);
        String dataSnapshotHash = snapshotHash(preview, approvalInstanceId, items, adjustments);
        String periodKey = preview.issueDate().format(DateTimeFormatter.ofPattern("yyyyMM"));
        long sequence = numberSequenceService.next(tenantId, "invoice", periodKey, 6);
        String invoiceNumber = "INV-%s-%06d".formatted(periodKey, sequence);
        UUID invoiceId = UuidV7.generate();

        int transitioned = jdbc.sql("""
                        UPDATE invoice_previews
                        SET status = 'FINALIZING', updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :previewId AND status = 'APPROVED' AND version = :version
                        """)
                .param("tenantId", tenantId)
                .param("previewId", previewId)
                .param("version", preview.version())
                .update();
        if (transitioned != 1) {
            throw new DomainException("VERSION_CONFLICT", "Invoice preview was modified by another request", 409,
                    Map.of("expected_version", expectedVersion));
        }

        jdbc.sql("""
                        INSERT INTO invoices(
                            id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                            customer_id, company_id, template_id, template_version_id, approval_instance_id,
                            period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                            exchange_rate, subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                            party_snapshot_json, profile_snapshot_json, render_model_json, data_snapshot_hash,
                            document_status, finalized_by
                        ) VALUES (
                            :id, :tenantId, :invoiceNumber, :previewId, :profileId,
                            :customerId, :companyId, :templateId, :templateVersionId, :approvalInstanceId,
                            :periodStart, :periodEnd, :issueDate, :dueDate, :timezone, :language, :currency,
                            :exchangeRate, :subtotal, :discount, :tax, :adjustment, :total,
                            CAST(:partySnapshot AS jsonb), CAST(:profileSnapshot AS jsonb), CAST(:renderModel AS jsonb),
                            :dataSnapshotHash, 'FINALIZING', :actorId
                        )
                        """)
                .param("id", invoiceId)
                .param("tenantId", tenantId)
                .param("invoiceNumber", invoiceNumber)
                .param("previewId", previewId)
                .param("profileId", preview.invoiceProfileId())
                .param("customerId", preview.customerId())
                .param("companyId", preview.companyId())
                .param("templateId", preview.templateId())
                .param("templateVersionId", preview.templateVersionId())
                .param("approvalInstanceId", approvalInstanceId)
                .param("periodStart", preview.periodStart())
                .param("periodEnd", preview.periodEnd())
                .param("issueDate", preview.issueDate())
                .param("dueDate", preview.dueDate())
                .param("timezone", preview.timezone())
                .param("language", preview.language())
                .param("currency", preview.currencyCode())
                .param("exchangeRate", preview.exchangeRate())
                .param("subtotal", preview.subtotalMinor())
                .param("discount", preview.discountMinor())
                .param("tax", preview.taxMinor())
                .param("adjustment", preview.adjustmentMinor())
                .param("total", preview.totalMinor())
                .param("partySnapshot", preview.partySnapshot())
                .param("profileSnapshot", preview.profileSnapshot())
                .param("renderModel", preview.renderModel())
                .param("dataSnapshotHash", dataSnapshotHash)
                .param("actorId", actorId)
                .update();

        for (PreviewItem item : items) {
            insertItem(tenantId, invoiceId, item);
        }
        for (PreviewAdjustment adjustment : adjustments) {
            insertAdjustment(tenantId, invoiceId, adjustment);
        }
        outbox.append(tenantId, "invoice", invoiceId, "invoice.finalization-requested", 1,
                Map.of("invoice_id", invoiceId, "invoice_number", invoiceNumber,
                        "preview_id", previewId, "data_snapshot_hash", dataSnapshotHash), Map.of());
        UUID jobId = enqueueRender(tenantId, invoiceId, dataSnapshotHash);
        return new FinalizationResult(invoiceId, invoiceNumber, jobId, "FINALIZING");
    }

    private void lockFinalizationEvidence(UUID tenantId, UUID previewId) {
        jdbc.sql("""
                        SELECT snapshot.id
                        FROM usage_snapshots snapshot
                        WHERE snapshot.tenant_id = :tenantId
                          AND snapshot.id IN (
                              SELECT item.usage_snapshot_id
                              FROM invoice_preview_items item
                              LEFT JOIN invoice_preview_exclusions exclusion
                                ON exclusion.tenant_id = item.tenant_id
                               AND exclusion.invoice_preview_item_id = item.id
                              WHERE item.tenant_id = :tenantId
                                AND item.invoice_preview_id = :previewId
                                AND item.usage_snapshot_id IS NOT NULL
                                AND exclusion.id IS NULL
                          )
                        ORDER BY snapshot.id
                        FOR UPDATE OF snapshot
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query(UUID.class).list();
        jdbc.sql("""
                        SELECT file.id
                        FROM files file
                        WHERE file.tenant_id = :tenantId
                          AND file.id IN (
                              SELECT link.file_id
                              FROM usage_snapshot_files link
                              JOIN invoice_preview_items item
                                ON item.tenant_id = link.tenant_id
                               AND item.usage_snapshot_id = link.usage_snapshot_id
                              LEFT JOIN invoice_preview_exclusions exclusion
                                ON exclusion.tenant_id = item.tenant_id
                               AND exclusion.invoice_preview_item_id = item.id
                              WHERE item.tenant_id = :tenantId
                                AND item.invoice_preview_id = :previewId
                                AND exclusion.id IS NULL
                              UNION
                              SELECT adjustment.attachment_file_id
                              FROM invoice_preview_adjustments adjustment
                              WHERE adjustment.tenant_id = :tenantId
                                AND adjustment.invoice_preview_id = :previewId
                                AND adjustment.status = 'ACTIVE'
                                AND adjustment.attachment_file_id IS NOT NULL
                          )
                        ORDER BY file.id
                        FOR UPDATE OF file
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query(UUID.class).list();
    }

    private void lockCorrectionOrigin(UUID tenantId, UUID originInvoiceId) {
        if (originInvoiceId == null) {
            return;
        }
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
                        "The correction preview origin invoice was not found", 404,
                        Map.of("origin_invoice_id", originInvoiceId)));
        if (!"VOIDED".equals(status)) {
            throw new DomainException("ORIGIN_INVOICE_NOT_REPLACEABLE",
                    "The correction preview origin is no longer a voided invoice", 409,
                    Map.of("origin_invoice_id", originInvoiceId, "status", status));
        }
    }

    private void insertItem(UUID tenantId, UUID invoiceId, PreviewItem item) {
        jdbc.sql("""
                        INSERT INTO invoice_items(
                            id, tenant_id, invoice_id, source_preview_item_id,
                            contract_item_id, service_id, pricing_rule_version_id,
                            usage_snapshot_id, source_key, line_no, item_name, item_description,
                            billing_period_start, billing_period_end, raw_usage, converted_usage, rounded_usage,
                            billing_usage, quantity, unit, unit_price, subtotal_minor, discount_minor, tax_minor,
                            total_minor, calculation_snapshot_json, display_json
                        ) VALUES (
                            :id, :tenantId, :invoiceId, :sourcePreviewItemId,
                            :contractItemId, :serviceId, :pricingVersionId,
                            :usageSnapshotId, :sourceKey, :lineNo, :itemName, :description,
                            :periodStart, :periodEnd, :rawUsage, :convertedUsage, :roundedUsage,
                            :billingUsage, :quantity, :unit, :unitPrice, :subtotal, :discount, :tax,
                            :total, CAST(:calculation AS jsonb), CAST(:display AS jsonb)
                        )
                        """)
                .param("id", UuidV7.generate()).param("tenantId", tenantId).param("invoiceId", invoiceId)
                .param("sourcePreviewItemId", item.id())
                .param("contractItemId", item.contractItemId()).param("serviceId", item.serviceId())
                .param("pricingVersionId", item.pricingRuleVersionId()).param("usageSnapshotId", item.usageSnapshotId())
                .param("sourceKey", item.sourceKey()).param("lineNo", item.lineNo()).param("itemName", item.itemName())
                .param("description", item.description()).param("periodStart", item.periodStart()).param("periodEnd", item.periodEnd())
                .param("rawUsage", item.rawUsage()).param("convertedUsage", item.convertedUsage()).param("roundedUsage", item.roundedUsage())
                .param("billingUsage", item.billingUsage()).param("quantity", item.quantity()).param("unit", item.unit())
                .param("unitPrice", item.unitPrice()).param("subtotal", item.subtotalMinor()).param("discount", item.discountMinor())
                .param("tax", item.taxMinor()).param("total", item.totalMinor()).param("calculation", item.calculationSnapshot())
                .param("display", item.display()).update();
    }

    private void insertAdjustment(UUID tenantId, UUID invoiceId, PreviewAdjustment adjustment) {
        jdbc.sql("""
                        INSERT INTO invoice_adjustments(
                            id, tenant_id, invoice_id, source_preview_adjustment_id, adjustment_type,
                            description, amount_minor, tax_rate, included_in_tax_base, reason,
                            attachment_file_id, operator_snapshot_json
                        ) VALUES (
                            :id, :tenantId, :invoiceId, :sourceId, :type,
                            :description, :amount, :taxRate, :included, :reason,
                            :attachmentId, CAST(:operatorSnapshot AS jsonb)
                        )
                        """)
                .param("id", UuidV7.generate()).param("tenantId", tenantId).param("invoiceId", invoiceId)
                .param("sourceId", adjustment.id()).param("type", adjustment.type()).param("description", adjustment.description())
                .param("amount", adjustment.amountMinor()).param("taxRate", adjustment.taxRate()).param("included", adjustment.includedInTaxBase())
                .param("reason", adjustment.reason()).param("attachmentId", adjustment.attachmentFileId())
                .param("operatorSnapshot", adjustment.operatorSnapshot()).update();
    }

    private List<PreviewItem> loadItems(UUID tenantId, UUID previewId) {
        return jdbc.sql("""
                        SELECT item.* FROM invoice_preview_items item
                        LEFT JOIN invoice_preview_exclusions exclusion
                          ON exclusion.tenant_id = item.tenant_id
                         AND exclusion.invoice_preview_item_id = item.id
                        WHERE item.tenant_id = :tenantId AND item.invoice_preview_id = :previewId
                          AND exclusion.id IS NULL
                        ORDER BY item.line_no
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query(this::mapItem).list();
    }

    private List<PreviewAdjustment> loadAdjustments(UUID tenantId, UUID previewId) {
        return jdbc.sql("""
                        SELECT adjustment.*, jsonb_build_object(
                            'created_by', adjustment.created_by,
                            'approved_by', adjustment.approved_by,
                            'created_at', adjustment.created_at
                        ) AS operator_snapshot_json
                        FROM invoice_preview_adjustments adjustment
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId AND status = 'ACTIVE'
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query(this::mapAdjustment).list();
    }

    private ExistingInvoice findExisting(UUID tenantId, UUID previewId) {
        return jdbc.sql("""
                        SELECT id, invoice_number, data_snapshot_hash, document_status
                        FROM invoices WHERE tenant_id = :tenantId AND source_preview_id = :previewId
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query((rs, rowNum) -> new ExistingInvoice(rs.getObject("id", UUID.class),
                        rs.getString("invoice_number"), rs.getString("data_snapshot_hash"), rs.getString("document_status")))
                .optional().orElse(null);
    }

    private UUID enqueueRender(UUID tenantId, UUID invoiceId, String dataSnapshotHash) {
        JsonNode payload = objectMapper.createObjectNode().put("invoice_id", invoiceId.toString());
        return jobService.enqueue(tenantId, "RENDER_INVOICE_PDF",
                "invoice-pdf:" + invoiceId + ":" + dataSnapshotHash, payload);
    }

    private String snapshotHash(Preview preview, UUID approvalInstanceId,
                                List<PreviewItem> items, List<PreviewAdjustment> adjustments) {
        try {
            FreezeSnapshot snapshot = new FreezeSnapshot(preview, approvalInstanceId, items, adjustments);
            byte[] canonical = objectMapper.writeValueAsBytes(snapshot);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash frozen invoice snapshot", exception);
        }
    }

    private Preview mapPreview(ResultSet rs, int rowNum) throws SQLException {
        return new Preview(
                rs.getObject("id", UUID.class), rs.getObject("invoice_profile_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("template_id", UUID.class), rs.getObject("template_version_id", UUID.class),
                rs.getObject("origin_invoice_id", UUID.class),
                rs.getObject("period_start", OffsetDateTime.class), rs.getObject("period_end", OffsetDateTime.class),
                rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getString("timezone"), rs.getString("language"), rs.getString("currency_code"),
                rs.getBigDecimal("exchange_rate"), rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
                rs.getLong("tax_minor"), rs.getLong("adjustment_minor"), rs.getLong("total_minor"),
                rs.getString("party_snapshot_json"), rs.getString("profile_snapshot_json"), rs.getString("render_model_json"),
                rs.getString("calculation_hash"), rs.getString("status"), rs.getLong("approval_revision"), rs.getLong("version"));
    }

    private PreviewItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new PreviewItem(
                rs.getObject("id", UUID.class), rs.getObject("contract_item_id", UUID.class),
                rs.getObject("service_id", UUID.class),
                rs.getObject("pricing_rule_version_id", UUID.class), rs.getObject("usage_snapshot_id", UUID.class),
                rs.getString("source_key"), rs.getInt("line_no"), rs.getString("item_name"), rs.getString("item_description"),
                rs.getObject("billing_period_start", OffsetDateTime.class), rs.getObject("billing_period_end", OffsetDateTime.class),
                rs.getBigDecimal("raw_usage"), rs.getBigDecimal("converted_usage"), rs.getBigDecimal("rounded_usage"),
                rs.getBigDecimal("billing_usage"), rs.getBigDecimal("quantity"), rs.getString("unit"), rs.getBigDecimal("unit_price"),
                rs.getLong("subtotal_minor"), rs.getLong("discount_minor"), rs.getLong("tax_minor"), rs.getLong("total_minor"),
                rs.getString("calculation_snapshot_json"), rs.getString("display_json"));
    }

    private PreviewAdjustment mapAdjustment(ResultSet rs, int rowNum) throws SQLException {
        return new PreviewAdjustment(rs.getObject("id", UUID.class), rs.getString("adjustment_type"),
                rs.getString("description"), rs.getLong("amount_minor"), rs.getBigDecimal("tax_rate"),
                rs.getBoolean("included_in_tax_base"), rs.getString("reason"),
                rs.getObject("attachment_file_id", UUID.class), rs.getString("operator_snapshot_json"));
    }

    public record FinalizationResult(UUID invoiceId, String invoiceNumber, UUID jobId, String status) {
    }

    private record ExistingInvoice(UUID invoiceId, String invoiceNumber, String dataSnapshotHash, String status) {
    }

    private record FreezeSnapshot(Preview preview, UUID approvalInstanceId,
                                  List<PreviewItem> items, List<PreviewAdjustment> adjustments) {
    }

    private record Preview(UUID id, UUID invoiceProfileId, UUID customerId, UUID companyId,
                           UUID templateId, UUID templateVersionId, UUID originInvoiceId,
                           OffsetDateTime periodStart, OffsetDateTime periodEnd,
                           LocalDate issueDate, LocalDate dueDate, String timezone, String language, String currencyCode,
                           BigDecimal exchangeRate, long subtotalMinor, long discountMinor, long taxMinor,
                           long adjustmentMinor, long totalMinor, String partySnapshot, String profileSnapshot,
                           String renderModel, String calculationHash, String status, long approvalRevision, long version) {
    }

    private record PreviewItem(UUID id, UUID contractItemId, UUID serviceId,
                               UUID pricingRuleVersionId, UUID usageSnapshotId,
                               String sourceKey, int lineNo, String itemName, String description,
                               OffsetDateTime periodStart, OffsetDateTime periodEnd, BigDecimal rawUsage,
                               BigDecimal convertedUsage, BigDecimal roundedUsage, BigDecimal billingUsage,
                               BigDecimal quantity, String unit, BigDecimal unitPrice, long subtotalMinor,
                               long discountMinor, long taxMinor, long totalMinor,
                               String calculationSnapshot, String display) {
    }

    private record PreviewAdjustment(UUID id, String type, String description, long amountMinor,
                                     BigDecimal taxRate, boolean includedInTaxBase, String reason,
                                     UUID attachmentFileId, String operatorSnapshot) {
    }
}
