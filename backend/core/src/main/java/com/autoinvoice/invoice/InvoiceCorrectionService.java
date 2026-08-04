package com.autoinvoice.invoice;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.outbox.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InvoiceCorrectionService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final OutboxService outbox;

    public InvoiceCorrectionService(JdbcClient jdbc, ObjectMapper objectMapper, OutboxService outbox) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
    }

    @Transactional
    public VoidResult voidInvoice(UUID tenantId, UUID invoiceId, UUID actorId,
                                  long expectedVersion, String reason) {
        FormalInvoice invoice = lockInvoice(tenantId, invoiceId);
        requireVersion(invoice, expectedVersion);
        if ("VOIDED".equals(invoice.documentStatus()) || "REPLACED".equals(invoice.documentStatus())) {
            return new VoidResult(invoice.id(), invoice.documentStatus(), invoice.version(), false);
        }
        if (!List.of("FINALIZING", "CONFIRMED", "SENT").contains(invoice.documentStatus())) {
            throw new DomainException("INVOICE_NOT_VOIDABLE", "Invoice is not in a voidable state", 409,
                    Map.of("invoice_id", invoiceId, "document_status", invoice.documentStatus()));
        }
        Long allocatedMinor = jdbc.sql("""
                        SELECT COALESCE(sum(amount_minor), 0)
                        FROM payment_allocations
                        WHERE tenant_id = :tenantId AND invoice_id = :invoiceId AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(Long.class).single();
        if (allocatedMinor > 0) {
            throw new DomainException("INVOICE_HAS_ACTIVE_PAYMENT",
                    "Active payment allocations must be reversed or refunded before voiding the invoice", 409,
                    Map.of("invoice_id", invoiceId, "allocated_minor", allocatedMinor));
        }

        long version = jdbc.sql("""
                        UPDATE invoices
                        SET document_status = 'VOIDED', voided_at = COALESCE(voided_at, clock_timestamp()),
                            updated_at = clock_timestamp(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :invoiceId AND version = :expectedVersion
                        RETURNING version
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId)
                .param("expectedVersion", expectedVersion).query(Long.class).optional()
                .orElseThrow(() -> versionConflict(invoiceId, expectedVersion));

        jdbc.sql("""
                        UPDATE notification_logs
                        SET status = 'CANCELLED', last_error_code = 'INVOICE_VOIDED',
                            last_error_message = :reason, next_attempt_at = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND invoice_id = :invoiceId
                          AND status IN ('PENDING', 'RETRY', 'FAILED', 'DEAD')
                        """)
                .param("reason", reason).param("tenantId", tenantId).param("invoiceId", invoiceId).update();
        jdbc.sql("""
                        UPDATE background_jobs job
                        SET status = 'CANCELLED', leased_by = NULL, leased_until = NULL, updated_at = now()
                        WHERE job.tenant_id = :tenantId AND job.job_type = 'SEND_NOTIFICATION'
                          AND job.status IN ('PENDING', 'RETRY')
                          AND EXISTS (
                              SELECT 1 FROM notification_logs notification
                              WHERE notification.tenant_id = job.tenant_id
                                AND notification.invoice_id = :invoiceId
                                AND job.payload_json ->> 'notification_id' = notification.id::text
                          )
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).update();
        outbox.append(tenantId, "invoice", invoiceId, "invoice.voided", 1,
                Map.of("invoice_id", invoiceId, "invoice_number", invoice.invoiceNumber(),
                        "reason", reason, "actor_id", actorId, "version", version), Map.of());
        return new VoidResult(invoiceId, "VOIDED", version, true);
    }

    @Transactional
    public ReplacementPreviewResult createReplacementPreview(UUID tenantId, UUID invoiceId, UUID actorId,
                                                               long expectedVersion, String reason) {
        FormalInvoice invoice = lockInvoice(tenantId, invoiceId);
        requireVersion(invoice, expectedVersion);
        if (!List.of("VOIDED", "REPLACED").contains(invoice.documentStatus())) {
            throw new DomainException("INVOICE_MUST_BE_VOIDED",
                    "The original invoice must be voided before a replacement preview is created", 409,
                    Map.of("invoice_id", invoiceId, "document_status", invoice.documentStatus()));
        }
        ReplacementPreviewResult existing = jdbc.sql("""
                        SELECT id, preview_number, status, version
                        FROM invoice_previews
                        WHERE tenant_id = :tenantId AND origin_invoice_id = :invoiceId
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId)
                .query((rs, row) -> new ReplacementPreviewResult(rs.getObject("id", UUID.class),
                        rs.getString("preview_number"), rs.getString("status"), rs.getLong("version"), false))
                .optional().orElse(null);
        if (existing != null) {
            return existing;
        }

        UUID previewId = UuidV7.generate();
        String suffix = previewId.toString().replace("-", "").substring(0, 8).toUpperCase();
        String previewNumber = "COR-" + invoice.invoiceNumber() + "-" + suffix;
        ObjectNode renderModel = object(invoice.renderModelJson(), "render_model_json");
        renderModel.put("preview_number", previewNumber);
        renderModel.put("correction_of_invoice_id", invoice.id().toString());
        renderModel.put("correction_of_invoice_number", invoice.invoiceNumber());
        renderModel.put("correction_reason", reason);
        ArrayNode anomalies = objectMapper.createArrayNode();
        anomalies.addObject().put("code", "REPLACEMENT_PREVIEW")
                .put("message", "This preview replaces voided invoice " + invoice.invoiceNumber())
                .put("blocking", false);

        jdbc.sql("""
                        INSERT INTO invoice_previews(
                            id, tenant_id, preview_number, invoice_profile_id, customer_id, company_id,
                            template_id, template_version_id, approval_workflow_version_id, origin_invoice_id,
                            period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                            exchange_rate, subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                            profile_snapshot_json, party_snapshot_json, render_model_json, anomaly_json,
                            calculation_hash, status, approval_revision, generated_at, created_by
                        ) VALUES (
                            :id, :tenantId, :previewNumber, :profileId, :customerId, :companyId,
                            :templateId, :templateVersionId, :workflowVersionId, :originInvoiceId,
                            :periodStart, :periodEnd, :issueDate, :dueDate, :timezone, :language, :currency,
                            :exchangeRate, :subtotal, :discount, :tax, :adjustment, :total,
                            CAST(:profileSnapshot AS jsonb), CAST(:partySnapshot AS jsonb),
                            CAST(:renderModel AS jsonb), CAST(:anomalies AS jsonb),
                            NULL, 'DRAFT', 0, now(), :actorId
                        )
                        """)
                .param("id", previewId).param("tenantId", tenantId).param("previewNumber", previewNumber)
                .param("profileId", invoice.profileId()).param("customerId", invoice.customerId())
                .param("companyId", invoice.companyId()).param("templateId", invoice.templateId())
                .param("templateVersionId", invoice.templateVersionId())
                .param("workflowVersionId", invoice.approvalWorkflowVersionId())
                .param("originInvoiceId", invoice.id()).param("periodStart", invoice.periodStart())
                .param("periodEnd", invoice.periodEnd()).param("issueDate", invoice.issueDate())
                .param("dueDate", invoice.dueDate()).param("timezone", invoice.timezone())
                .param("language", invoice.language()).param("currency", invoice.currencyCode())
                .param("exchangeRate", invoice.exchangeRate()).param("subtotal", invoice.subtotalMinor())
                .param("discount", invoice.discountMinor()).param("tax", invoice.taxMinor())
                .param("adjustment", invoice.adjustmentMinor()).param("total", invoice.totalMinor())
                .param("profileSnapshot", invoice.profileSnapshotJson()).param("partySnapshot", invoice.partySnapshotJson())
                .param("renderModel", renderModel.toString()).param("anomalies", anomalies.toString())
                .param("actorId", actorId).update();

        for (FrozenItem item : loadItems(tenantId, invoiceId)) {
            jdbc.sql("""
                            INSERT INTO invoice_preview_items(
                                id, tenant_id, invoice_preview_id, contract_item_id, service_id,
                                pricing_rule_version_id, usage_snapshot_id, source_key, line_no,
                                item_name, item_description, billing_period_start, billing_period_end,
                                raw_usage, converted_usage, rounded_usage, billing_usage, quantity, unit, unit_price,
                                subtotal_minor, discount_minor, tax_minor, total_minor,
                                calculation_snapshot_json, display_json
                            ) VALUES (
                                :id, :tenantId, :previewId, :contractItemId, :serviceId,
                                :pricingVersionId, :usageSnapshotId, :sourceKey, :lineNo,
                                :itemName, :description, :periodStart, :periodEnd,
                                :rawUsage, :convertedUsage, :roundedUsage, :billingUsage, :quantity, :unit, :unitPrice,
                                :subtotal, :discount, :tax, :total,
                                CAST(:calculation AS jsonb), CAST(:display AS jsonb)
                            )
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", tenantId).param("previewId", previewId)
                    .param("contractItemId", item.contractItemId()).param("serviceId", item.serviceId())
                    .param("pricingVersionId", item.pricingVersionId()).param("usageSnapshotId", item.usageSnapshotId())
                    .param("sourceKey", item.sourceKey()).param("lineNo", item.lineNo())
                    .param("itemName", item.itemName()).param("description", item.description())
                    .param("periodStart", item.periodStart()).param("periodEnd", item.periodEnd())
                    .param("rawUsage", item.rawUsage()).param("convertedUsage", item.convertedUsage())
                    .param("roundedUsage", item.roundedUsage()).param("billingUsage", item.billingUsage())
                    .param("quantity", item.quantity()).param("unit", item.unit()).param("unitPrice", item.unitPrice())
                    .param("subtotal", item.subtotalMinor()).param("discount", item.discountMinor())
                    .param("tax", item.taxMinor()).param("total", item.totalMinor())
                    .param("calculation", item.calculationJson()).param("display", item.displayJson()).update();
        }
        for (FrozenAdjustment adjustment : loadAdjustments(tenantId, invoiceId)) {
            jdbc.sql("""
                            INSERT INTO invoice_preview_adjustments(
                                id, tenant_id, invoice_preview_id, adjustment_type, description,
                                amount_minor, tax_rate, included_in_tax_base, reason,
                                attachment_file_id, status, created_by
                            ) VALUES (
                                :id, :tenantId, :previewId, :type, :description,
                                :amount, :taxRate, :included, :reason,
                                :attachmentId, 'ACTIVE', :actorId
                            )
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", tenantId).param("previewId", previewId)
                    .param("type", adjustment.type()).param("description", adjustment.description())
                    .param("amount", adjustment.amountMinor()).param("taxRate", adjustment.taxRate())
                    .param("included", adjustment.includedInTaxBase()).param("reason", adjustment.reason())
                    .param("attachmentId", adjustment.attachmentId()).param("actorId", actorId).update();
        }
        outbox.append(tenantId, "invoice_preview", previewId, "invoice.replacement-preview-created", 1,
                Map.of("invoice_id", invoiceId, "preview_id", previewId, "preview_number", previewNumber,
                        "reason", reason, "actor_id", actorId), Map.of());
        return new ReplacementPreviewResult(previewId, previewNumber, "DRAFT", 0, true);
    }

    private FormalInvoice lockInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("""
                        SELECT invoice.*, approval.workflow_version_id
                        FROM invoices invoice
                        LEFT JOIN approval_instances approval
                          ON approval.tenant_id = invoice.tenant_id AND approval.id = invoice.approval_instance_id
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                        FOR UPDATE OF invoice
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId)
                .query(this::mapInvoice).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice was not found", 404,
                        Map.of("invoice_id", invoiceId)));
    }

    private List<FrozenItem> loadItems(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("SELECT * FROM invoice_items WHERE tenant_id = :tenantId AND invoice_id = :invoiceId ORDER BY line_no")
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapItem).list();
    }

    private List<FrozenAdjustment> loadAdjustments(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("SELECT * FROM invoice_adjustments WHERE tenant_id = :tenantId AND invoice_id = :invoiceId ORDER BY created_at, id")
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query((rs, row) -> new FrozenAdjustment(
                        rs.getString("adjustment_type"), rs.getString("description"), rs.getLong("amount_minor"),
                        rs.getBigDecimal("tax_rate"), rs.getBoolean("included_in_tax_base"), rs.getString("reason"),
                        rs.getObject("attachment_file_id", UUID.class))).list();
    }

    private FormalInvoice mapInvoice(ResultSet rs, int row) throws SQLException {
        return new FormalInvoice(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getObject("invoice_profile_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("template_version_id", UUID.class), rs.getObject("workflow_version_id", UUID.class),
                rs.getObject("period_start", OffsetDateTime.class), rs.getObject("period_end", OffsetDateTime.class),
                rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getString("timezone"), rs.getString("language"), rs.getString("currency_code"),
                rs.getBigDecimal("exchange_rate"), rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
                rs.getLong("tax_minor"), rs.getLong("adjustment_minor"), rs.getLong("total_minor"),
                rs.getString("party_snapshot_json"), rs.getString("profile_snapshot_json"),
                rs.getString("render_model_json"), rs.getString("document_status"), rs.getLong("version"));
    }

    private FrozenItem mapItem(ResultSet rs, int row) throws SQLException {
        return new FrozenItem(rs.getObject("contract_item_id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getObject("pricing_rule_version_id", UUID.class), rs.getObject("usage_snapshot_id", UUID.class),
                rs.getString("source_key"), rs.getInt("line_no"), rs.getString("item_name"),
                rs.getString("item_description"), rs.getObject("billing_period_start", OffsetDateTime.class),
                rs.getObject("billing_period_end", OffsetDateTime.class), rs.getBigDecimal("raw_usage"),
                rs.getBigDecimal("converted_usage"), rs.getBigDecimal("rounded_usage"),
                rs.getBigDecimal("billing_usage"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getBigDecimal("unit_price"), rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
                rs.getLong("tax_minor"), rs.getLong("total_minor"), rs.getString("calculation_snapshot_json"),
                rs.getString("display_json"));
    }

    private ObjectNode object(String json, String field) {
        try {
            JsonNode value = objectMapper.readTree(json);
            if (!value.isObject()) {
                throw new IllegalStateException(field + " must contain a JSON object");
            }
            return (ObjectNode) value.deepCopy();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted " + field + " is invalid", exception);
        }
    }

    private void requireVersion(FormalInvoice invoice, long expectedVersion) {
        if (invoice.version() != expectedVersion) {
            throw versionConflict(invoice.id(), expectedVersion);
        }
    }

    private DomainException versionConflict(UUID invoiceId, long expectedVersion) {
        return new DomainException("VERSION_CONFLICT", "Invoice was modified by another request", 409,
                Map.of("invoice_id", invoiceId, "expected_version", expectedVersion));
    }

    public record VoidResult(UUID invoiceId, String documentStatus, long version, boolean changed) {
    }

    public record ReplacementPreviewResult(UUID previewId, String previewNumber,
                                           String status, long version, boolean created) {
    }

    private record FormalInvoice(UUID id, String invoiceNumber, UUID profileId, UUID customerId,
                                 UUID companyId, UUID templateId, UUID templateVersionId,
                                 UUID approvalWorkflowVersionId, OffsetDateTime periodStart,
                                 OffsetDateTime periodEnd, LocalDate issueDate, LocalDate dueDate,
                                 String timezone, String language, String currencyCode, BigDecimal exchangeRate,
                                 long subtotalMinor, long discountMinor, long taxMinor, long adjustmentMinor,
                                 long totalMinor, String partySnapshotJson, String profileSnapshotJson,
                                 String renderModelJson, String documentStatus, long version) {
    }

    private record FrozenItem(UUID contractItemId, UUID serviceId, UUID pricingVersionId,
                              UUID usageSnapshotId, String sourceKey, int lineNo, String itemName,
                              String description, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                              BigDecimal rawUsage, BigDecimal convertedUsage, BigDecimal roundedUsage,
                              BigDecimal billingUsage, BigDecimal quantity, String unit, BigDecimal unitPrice,
                              long subtotalMinor, long discountMinor, long taxMinor, long totalMinor,
                              String calculationJson, String displayJson) {
    }

    private record FrozenAdjustment(String type, String description, long amountMinor, BigDecimal taxRate,
                                    boolean includedInTaxBase, String reason, UUID attachmentId) {
    }
}
