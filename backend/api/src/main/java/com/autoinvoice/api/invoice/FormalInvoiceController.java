package com.autoinvoice.api.invoice;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.invoice.InvoiceCorrectionService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
public class FormalInvoiceController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final InvoiceCorrectionService correctionService;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public FormalInvoiceController(JdbcClient jdbc, ObjectMapper objectMapper,
                                   InvoiceCorrectionService correctionService,
                                   IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.correctionService = correctionService;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasAnyAuthority('invoice.finalize','invoice.send','invoice.void','payment.record','audit.read')")
    public ResponseEntity<InvoiceDetail> detail(Authentication authentication, @PathVariable UUID invoiceId) {
        UUID tenantId = principal(authentication).tenantId();
        InvoiceHeader header = jdbc.sql("SELECT * FROM invoices WHERE tenant_id = :tenantId AND id = :invoiceId")
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapHeader).optional()
                .orElseThrow(() -> notFound(invoiceId));
        List<InvoiceItem> items = jdbc.sql("""
                        SELECT * FROM invoice_items
                        WHERE tenant_id = :tenantId AND invoice_id = :invoiceId ORDER BY line_no
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapItem).list();
        List<InvoiceAdjustment> adjustments = jdbc.sql("""
                        SELECT * FROM invoice_adjustments
                        WHERE tenant_id = :tenantId AND invoice_id = :invoiceId ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapAdjustment).list();
        List<InvoiceFile> files = jdbc.sql("""
                        SELECT invoice_file.id, invoice_file.file_id, invoice_file.file_role,
                               invoice_file.template_version_id, invoice_file.renderer_version,
                               invoice_file.chromium_version, invoice_file.font_manifest_json,
                               invoice_file.content_sha256, invoice_file.created_at,
                               file.original_filename, file.mime_type, file.file_size
                        FROM invoice_files invoice_file
                        JOIN files file ON file.tenant_id = invoice_file.tenant_id AND file.id = invoice_file.file_id
                        WHERE invoice_file.tenant_id = :tenantId AND invoice_file.invoice_id = :invoiceId
                        ORDER BY invoice_file.created_at, invoice_file.id
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapFile).list();
        List<InvoiceRelation> relations = jdbc.sql("""
                        SELECT relation.*, source.invoice_number AS source_number,
                               target.invoice_number AS target_number
                        FROM invoice_relations relation
                        JOIN invoices source ON source.tenant_id = relation.tenant_id
                             AND source.id = relation.source_invoice_id
                        JOIN invoices target ON target.tenant_id = relation.tenant_id
                             AND target.id = relation.target_invoice_id
                        WHERE relation.tenant_id = :tenantId
                          AND (relation.source_invoice_id = :invoiceId OR relation.target_invoice_id = :invoiceId)
                        ORDER BY relation.created_at, relation.id
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapRelation).list();
        return ResponseEntity.ok().eTag(VersionEtag.format(header.version()))
                .body(new InvoiceDetail(header, items, adjustments, files, relations));
    }

    @GetMapping("/{invoiceId}/pdf")
    @PreAuthorize("hasAnyAuthority('invoice.finalize','invoice.send','invoice.void','payment.record','audit.read')")
    public ResponseEntity<Void> pdf(Authentication authentication, @PathVariable UUID invoiceId) {
        UUID tenantId = principal(authentication).tenantId();
        UUID fileId = jdbc.sql("""
                        SELECT invoice_file.file_id
                        FROM invoice_files invoice_file
                        WHERE invoice_file.tenant_id = :tenantId AND invoice_file.invoice_id = :invoiceId
                          AND invoice_file.file_role = 'PDF'
                        ORDER BY invoice_file.created_at DESC LIMIT 1
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(UUID.class).optional()
                .orElseThrow(() -> new DomainException("INVOICE_PDF_NOT_READY",
                        "The formal invoice PDF is not available", 409, Map.of("invoice_id", invoiceId)));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/api/v1/files/" + fileId + "/content"))
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }

    @PostMapping("/{invoiceId}/void")
    @PreAuthorize("hasAuthority('invoice.void')")
    public ResponseEntity<InvoiceCorrectionService.VoidResult> voidInvoice(
            Authentication authentication, @PathVariable UUID invoiceId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody VersionedReasonRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoices/" + invoiceId + "/void";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoiceCorrectionService.VoidResult.class, () -> {
                    var result = correctionService.voidInvoice(actor.tenantId(), invoiceId, actor.userId(),
                            request.expectedVersion(), request.reason());
                    audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                            "invoice.voided", "invoice", invoiceId, null, result, request.reason(),
                            servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.ok().eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    @PostMapping("/{invoiceId}/create-replacement-preview")
    @PreAuthorize("hasAuthority('invoice.void')")
    public ResponseEntity<InvoiceCorrectionService.ReplacementPreviewResult> createReplacement(
            Authentication authentication, @PathVariable UUID invoiceId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody VersionedReasonRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoices/" + invoiceId + "/create-replacement-preview";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoiceCorrectionService.ReplacementPreviewResult.class, () -> {
                    var result = correctionService.createReplacementPreview(actor.tenantId(), invoiceId,
                            actor.userId(), request.expectedVersion(), request.reason());
                    audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                            "invoice.replacement_preview_created", "invoice_preview", result.previewId(),
                            Map.of("origin_invoice_id", invoiceId), result, request.reason(),
                            servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                            .eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    private InvoiceHeader mapHeader(ResultSet rs, int row) throws SQLException {
        return new InvoiceHeader(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getObject("source_preview_id", UUID.class), rs.getObject("invoice_profile_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("template_id", UUID.class), rs.getObject("template_version_id", UUID.class),
                rs.getObject("approval_instance_id", UUID.class), rs.getObject("period_start", OffsetDateTime.class),
                rs.getObject("period_end", OffsetDateTime.class), rs.getObject("issue_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class), rs.getString("timezone"), rs.getString("language"),
                rs.getString("currency_code"), rs.getBigDecimal("exchange_rate"), rs.getLong("subtotal_minor"),
                rs.getLong("discount_minor"), rs.getLong("tax_minor"), rs.getLong("adjustment_minor"),
                rs.getLong("total_minor"), json(rs.getString("party_snapshot_json")),
                json(rs.getString("profile_snapshot_json")), json(rs.getString("render_model_json")),
                rs.getString("data_snapshot_hash"), rs.getString("document_status"), rs.getString("send_status"),
                rs.getString("payment_status"), rs.getObject("finalized_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("confirmed_at", OffsetDateTime.class),
                rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("voided_at", OffsetDateTime.class),
                rs.getObject("paid_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private InvoiceItem mapItem(ResultSet rs, int row) throws SQLException {
        return new InvoiceItem(rs.getObject("id", UUID.class), rs.getObject("source_preview_item_id", UUID.class),
                rs.getObject("contract_item_id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getObject("pricing_rule_version_id", UUID.class), rs.getObject("usage_snapshot_id", UUID.class),
                rs.getString("source_key"), rs.getInt("line_no"), rs.getString("item_name"),
                rs.getString("item_description"), rs.getObject("billing_period_start", OffsetDateTime.class),
                rs.getObject("billing_period_end", OffsetDateTime.class), rs.getBigDecimal("raw_usage"),
                rs.getBigDecimal("converted_usage"), rs.getBigDecimal("rounded_usage"),
                rs.getBigDecimal("billing_usage"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getBigDecimal("unit_price"), rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
                rs.getLong("tax_minor"), rs.getLong("total_minor"), json(rs.getString("calculation_snapshot_json")),
                json(rs.getString("display_json")));
    }

    private InvoiceAdjustment mapAdjustment(ResultSet rs, int row) throws SQLException {
        return new InvoiceAdjustment(rs.getObject("id", UUID.class),
                rs.getObject("source_preview_adjustment_id", UUID.class), rs.getString("adjustment_type"),
                rs.getString("description"), rs.getLong("amount_minor"), rs.getBigDecimal("tax_rate"),
                rs.getBoolean("included_in_tax_base"), rs.getString("reason"),
                rs.getObject("attachment_file_id", UUID.class), json(rs.getString("operator_snapshot_json")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private InvoiceFile mapFile(ResultSet rs, int row) throws SQLException {
        return new InvoiceFile(rs.getObject("id", UUID.class), rs.getObject("file_id", UUID.class),
                rs.getString("file_role"), rs.getObject("template_version_id", UUID.class),
                rs.getString("renderer_version"), rs.getString("chromium_version"),
                json(rs.getString("font_manifest_json")), rs.getString("content_sha256"),
                rs.getString("original_filename"), rs.getString("mime_type"), rs.getLong("file_size"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private InvoiceRelation mapRelation(ResultSet rs, int row) throws SQLException {
        return new InvoiceRelation(rs.getObject("id", UUID.class), rs.getObject("source_invoice_id", UUID.class),
                rs.getString("source_number"), rs.getObject("target_invoice_id", UUID.class),
                rs.getString("target_number"), rs.getString("relation_type"), rs.getString("reason"),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode json(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted JSON is invalid", exception);
        }
    }

    private void assertVersion(String ifMatch, long expectedVersion) {
        long headerVersion = VersionEtag.parse(ifMatch);
        if (headerVersion != expectedVersion) {
            throw new DomainException("VERSION_CONFLICT",
                    "If-Match and expected_version must identify the same invoice version", 409,
                    Map.of("if_match_version", headerVersion, "expected_version", expectedVersion));
        }
    }

    private DomainException notFound(UUID invoiceId) {
        return new DomainException("RESOURCE_NOT_FOUND", "Invoice was not found", 404,
                Map.of("invoice_id", invoiceId));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    public record VersionedReasonRequest(@PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record InvoiceDetail(InvoiceHeader invoice, List<InvoiceItem> items,
                                List<InvoiceAdjustment> adjustments, List<InvoiceFile> files,
                                List<InvoiceRelation> relations) {
    }

    public record InvoiceHeader(UUID id, String invoiceNumber, UUID sourcePreviewId, UUID invoiceProfileId,
                                UUID customerId, UUID companyId, UUID templateId, UUID templateVersionId,
                                UUID approvalInstanceId, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                LocalDate issueDate, LocalDate dueDate, String timezone, String language,
                                String currencyCode, BigDecimal exchangeRate, long subtotalMinor, long discountMinor,
                                long taxMinor, long adjustmentMinor, long totalMinor, JsonNode partySnapshot,
                                JsonNode profileSnapshot, JsonNode renderModel, String dataSnapshotHash,
                                String documentStatus, String sendStatus, String paymentStatus, UUID finalizedBy,
                                OffsetDateTime createdAt, OffsetDateTime confirmedAt, OffsetDateTime sentAt,
                                OffsetDateTime voidedAt, OffsetDateTime paidAt, long version) {
    }

    public record InvoiceItem(UUID id, UUID sourcePreviewItemId, UUID contractItemId, UUID serviceId,
                              UUID pricingRuleVersionId, UUID usageSnapshotId, String sourceKey, int lineNo,
                              String itemName, String itemDescription, OffsetDateTime billingPeriodStart,
                              OffsetDateTime billingPeriodEnd, BigDecimal rawUsage, BigDecimal convertedUsage,
                              BigDecimal roundedUsage, BigDecimal billingUsage, BigDecimal quantity, String unit,
                              BigDecimal unitPrice, long subtotalMinor, long discountMinor, long taxMinor,
                              long totalMinor, JsonNode calculationSnapshot, JsonNode display) {
    }

    public record InvoiceAdjustment(UUID id, UUID sourcePreviewAdjustmentId, String adjustmentType,
                                    String description, long amountMinor, BigDecimal taxRate,
                                    boolean includedInTaxBase, String reason, UUID attachmentFileId,
                                    JsonNode operatorSnapshot, OffsetDateTime createdAt) {
    }

    public record InvoiceFile(UUID id, UUID fileId, String fileRole, UUID templateVersionId,
                              String rendererVersion, String chromiumVersion, JsonNode fontManifest,
                              String contentSha256, String filename, String mimeType, long fileSize,
                              OffsetDateTime createdAt) {
    }

    public record InvoiceRelation(UUID id, UUID sourceInvoiceId, String sourceInvoiceNumber,
                                  UUID targetInvoiceId, String targetInvoiceNumber, String relationType,
                                  String reason, UUID createdBy, OffsetDateTime createdAt) {
    }
}
