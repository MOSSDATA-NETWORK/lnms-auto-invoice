package com.autoinvoice.api.invoice;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.invoice.InvoiceFinalizationService;
import com.autoinvoice.invoice.InvoicePreviewWorkflowService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class InvoiceLifecycleController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final InvoiceFinalizationService finalizationService;
    private final InvoicePreviewWorkflowService workflowService;
    private final BackgroundJobService jobs;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public InvoiceLifecycleController(JdbcClient jdbc, ObjectMapper objectMapper,
                                      InvoiceFinalizationService finalizationService,
                                      InvoicePreviewWorkflowService workflowService,
                                      BackgroundJobService jobs, IdempotencyExecutor idempotency,
                                      AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.finalizationService = finalizationService;
        this.workflowService = workflowService;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/invoice-previews")
    @PreAuthorize("hasAnyAuthority('preview.generate','preview.adjust','preview.approve.business','preview.approve.finance','invoice.finalize')")
    public List<PreviewSummary> previews(Authentication authentication,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = principal(authentication).tenantId();
        return jdbc.sql("""
                        SELECT id, preview_number, customer_id, company_id, period_start, period_end,
                               issue_date, due_date, currency_code, total_minor, status, approval_revision, version
                        FROM invoice_previews
                        WHERE tenant_id = :tenantId AND (:status IS NULL OR status = :status)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("status", blankToNull(status))
                .param("limit", Math.max(1, Math.min(limit, 200))).query(this::mapPreview).list();
    }

    @GetMapping("/invoice-previews/{previewId}")
    @PreAuthorize("hasAnyAuthority('preview.generate','preview.adjust','preview.approve.business','preview.approve.finance','invoice.finalize')")
    public ResponseEntity<PreviewDetail> preview(Authentication authentication, @PathVariable UUID previewId) {
        UUID tenantId = principal(authentication).tenantId();
        PreviewHeader header = jdbc.sql("SELECT * FROM invoice_previews WHERE tenant_id = :tenantId AND id = :previewId")
                .param("tenantId", tenantId).param("previewId", previewId).query(this::mapPreviewHeader).optional()
                .orElseThrow(() -> notFound("preview_id", previewId));
        List<PreviewItem> items = jdbc.sql("""
                        SELECT item.*, exclusion.id AS exclusion_id, exclusion.reason AS exclusion_reason
                        FROM invoice_preview_items item
                        LEFT JOIN invoice_preview_exclusions exclusion
                          ON exclusion.tenant_id = item.tenant_id
                         AND exclusion.invoice_preview_item_id = item.id
                        WHERE item.tenant_id = :tenantId AND item.invoice_preview_id = :previewId
                        ORDER BY item.line_no
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query(this::mapPreviewItem).list();
        List<PreviewAdjustment> adjustments = jdbc.sql("""
                        SELECT * FROM invoice_preview_adjustments
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query(this::mapAdjustment).list();
        List<ApprovalAction> approvals = jdbc.sql("""
                        SELECT instance.id AS approval_instance_id, instance.status AS approval_status,
                               instance.approval_revision, instance.preview_version AS instance_preview_version,
                               instance.current_step_no, action.id AS action_id, action.action,
                               action.preview_version AS action_preview_version, action.actor_id,
                               action.actor_snapshot_json, action.comment, action.created_at
                        FROM approval_instances instance
                        LEFT JOIN approval_actions action ON action.tenant_id = instance.tenant_id
                             AND action.approval_instance_id = instance.id
                        WHERE instance.tenant_id = :tenantId AND instance.invoice_preview_id = :previewId
                        ORDER BY instance.approval_revision, action.created_at, action.id
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query(this::mapApprovalAction).list();
        return ResponseEntity.ok().eTag(VersionEtag.format(header.version()))
                .body(new PreviewDetail(header, items, adjustments, approvals));
    }

    @PostMapping("/invoice-previews/{previewId}/recalculate")
    @PreAuthorize("hasAuthority('preview.generate')")
    public ResponseEntity<JobAccepted> recalculate(Authentication authentication, @PathVariable UUID previewId,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                   @Valid @RequestBody VersionedReasonRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/recalculate";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                JobAccepted.class, () -> {
            requirePreview(actor.tenantId(), previewId, request.expectedVersion());
            JsonNode payload = objectMapper.createObjectNode().put("preview_id", previewId.toString())
                    .put("expected_version", request.expectedVersion())
                    .put("requested_by", actor.userId().toString());
            UUID jobId = jobs.enqueue(actor.tenantId(), "GENERATE_INVOICE_PREVIEW",
                    "preview-recalculate:" + previewId + ":" + request.expectedVersion(), payload);
            record(actor, "invoice_preview.recalculation_queued", "invoice_preview", previewId,
                    null, Map.of("job_id", jobId), request.reason(), servletRequest);
            return ResponseEntity.accepted().body(new JobAccepted(jobId));
        });
    }

    @PostMapping("/invoice-previews/{previewId}/adjustments")
    @PreAuthorize("hasAuthority('preview.adjust')")
    public ResponseEntity<InvoicePreviewWorkflowService.PreviewResult> addAdjustment(
            Authentication authentication, @PathVariable UUID previewId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody AdjustmentRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/adjustments";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoicePreviewWorkflowService.PreviewResult.class, () -> {
                    var result = workflowService.addAdjustment(actor.tenantId(), previewId, request.expectedVersion(),
                            actor.userId(), actor.permissions(), request.adjustmentType(), request.description(),
                            request.amountMinor(), request.taxRate(), request.includedInTaxBase(), request.reason(),
                            request.attachmentFileId());
                    record(actor, "invoice_preview.adjustment_added", "invoice_preview", previewId, null, result,
                            request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    @PostMapping("/invoice-previews/{previewId}/adjustments/{adjustmentId}/remove")
    @PreAuthorize("hasAuthority('preview.adjust')")
    public ResponseEntity<InvoicePreviewWorkflowService.PreviewResult> removeAdjustment(
            Authentication authentication, @PathVariable UUID previewId, @PathVariable UUID adjustmentId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody VersionedReasonRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/adjustments/" + adjustmentId + "/remove";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoicePreviewWorkflowService.PreviewResult.class, () -> {
                    var result = workflowService.removeAdjustment(actor.tenantId(), previewId, adjustmentId,
                            request.expectedVersion(), actor.userId(), request.reason());
                    record(actor, "invoice_preview.adjustment_removed", "invoice_preview", previewId, null, result,
                            request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    @PostMapping("/invoice-previews/{previewId}/items/{itemId}/exclude")
    @PreAuthorize("hasAuthority('preview.adjust')")
    public ResponseEntity<InvoicePreviewWorkflowService.PreviewResult> exclude(
            Authentication authentication, @PathVariable UUID previewId, @PathVariable UUID itemId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody VersionedReasonRequest request, HttpServletRequest servletRequest) {
        return exclusionCommand(authentication, previewId, itemId, key, ifMatch, request, servletRequest, true);
    }

    @PostMapping("/invoice-previews/{previewId}/items/{itemId}/include")
    @PreAuthorize("hasAuthority('preview.adjust')")
    public ResponseEntity<InvoicePreviewWorkflowService.PreviewResult> include(
            Authentication authentication, @PathVariable UUID previewId, @PathVariable UUID itemId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody VersionedReasonRequest request, HttpServletRequest servletRequest) {
        return exclusionCommand(authentication, previewId, itemId, key, ifMatch, request, servletRequest, false);
    }

    private ResponseEntity<InvoicePreviewWorkflowService.PreviewResult> exclusionCommand(
            Authentication authentication, UUID previewId, UUID itemId, String key, String ifMatch,
            VersionedReasonRequest request, HttpServletRequest servletRequest, boolean exclude) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String action = exclude ? "exclude" : "include";
        String path = "/api/v1/invoice-previews/" + previewId + "/items/" + itemId + "/" + action;
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoicePreviewWorkflowService.PreviewResult.class, () -> {
                    var result = exclude
                            ? workflowService.excludeItem(actor.tenantId(), previewId, itemId,
                            request.expectedVersion(), actor.userId(), request.reason())
                            : workflowService.includeItem(actor.tenantId(), previewId, itemId,
                            request.expectedVersion(), actor.userId(), request.reason());
                    record(actor, "invoice_preview.item_" + (exclude ? "excluded" : "included"),
                            "invoice_preview", previewId, null, result, request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    @PostMapping("/invoice-previews/{previewId}/submit-review")
    @PreAuthorize("hasAuthority('preview.generate')")
    public ResponseEntity<InvoicePreviewWorkflowService.ApprovalResult> submitReview(
            Authentication authentication, @PathVariable UUID previewId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ApprovalRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/submit-review";
        return approvalCommand(actor, key, path, request, servletRequest,
                () -> workflowService.submit(actor.tenantId(), previewId, request.expectedVersion(),
                        actor.userId(), actor.displayName(), request.comment()), "invoice_preview.review_submitted");
    }

    @PostMapping("/invoice-previews/{previewId}/approve-business")
    @PreAuthorize("hasAuthority('preview.approve.business')")
    public ResponseEntity<InvoicePreviewWorkflowService.ApprovalResult> approveBusiness(
            Authentication authentication, @PathVariable UUID previewId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ApprovalRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/approve-business";
        return approvalCommand(actor, key, path, request, servletRequest,
                () -> workflowService.approve(actor.tenantId(), previewId, request.expectedVersion(),
                        actor.userId(), actor.displayName(), "preview.approve.business", request.comment()),
                "invoice_preview.business_approved");
    }

    @PostMapping("/invoice-previews/{previewId}/approve-finance")
    @PreAuthorize("hasAuthority('preview.approve.finance')")
    public ResponseEntity<InvoicePreviewWorkflowService.ApprovalResult> approveFinance(
            Authentication authentication, @PathVariable UUID previewId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ApprovalRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/approve-finance";
        return approvalCommand(actor, key, path, request, servletRequest,
                () -> workflowService.approve(actor.tenantId(), previewId, request.expectedVersion(),
                        actor.userId(), actor.displayName(), "preview.approve.finance", request.comment()),
                "invoice_preview.finance_approved");
    }

    @PostMapping("/invoice-previews/{previewId}/reject")
    @PreAuthorize("hasAnyAuthority('preview.approve.business','preview.approve.finance')")
    public ResponseEntity<InvoicePreviewWorkflowService.ApprovalResult> reject(
            Authentication authentication, @PathVariable UUID previewId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ApprovalRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/reject";
        return approvalCommand(actor, key, path, request, servletRequest,
                () -> workflowService.reject(actor.tenantId(), previewId, request.expectedVersion(),
                        actor.userId(), actor.displayName(), request.comment()), "invoice_preview.rejected");
    }

    private ResponseEntity<InvoicePreviewWorkflowService.ApprovalResult> approvalCommand(
            AuthenticatedUser actor, String key, String path, ApprovalRequest request,
            HttpServletRequest servletRequest,
            java.util.function.Supplier<InvoicePreviewWorkflowService.ApprovalResult> command, String action) {
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoicePreviewWorkflowService.ApprovalResult.class, () -> {
                    var result = command.get();
                    record(actor, action, "invoice_preview", result.previewId(), null, result,
                            request.comment(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyAuthority('invoice.finalize','invoice.send','invoice.void','payment.record','audit.read')")
    public List<InvoiceSummary> invoices(Authentication authentication,
                                         @RequestParam(name = "document_status", required = false) String documentStatus,
                                         @RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = principal(authentication).tenantId();
        return jdbc.sql("""
                        SELECT id, invoice_number, source_preview_id, customer_id, company_id, issue_date,
                               due_date, currency_code, total_minor, document_status, send_status, payment_status,
                               created_at, version FROM invoices
                        WHERE tenant_id = :tenantId
                          AND (:documentStatus IS NULL OR document_status = :documentStatus)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("documentStatus", blankToNull(documentStatus))
                .param("limit", Math.max(1, Math.min(limit, 200))).query(this::mapInvoice).list();
    }

    @PostMapping("/invoice-previews/{previewId}/finalize")
    @PreAuthorize("hasAuthority('invoice.finalize')")
    public ResponseEntity<InvoiceFinalizationService.FinalizationResult> finalizePreview(
            Authentication authentication, @PathVariable UUID previewId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody FinalizeRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoice-previews/" + previewId + "/finalize";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                InvoiceFinalizationService.FinalizationResult.class, () -> {
                    var result = finalizationService.finalizeApprovedPreview(
                            actor.tenantId(), previewId, actor.userId(), request.expectedVersion());
                    record(actor, "invoice.finalization_started", "invoice", result.invoiceId(), null, result,
                            request.confirmationNote(), servletRequest);
                    return ResponseEntity.accepted().body(result);
                });
    }

    private void requirePreview(UUID tenantId, UUID previewId, long expectedVersion) {
        Long version = jdbc.sql("SELECT version FROM invoice_previews WHERE tenant_id = :tenantId AND id = :previewId")
                .param("tenantId", tenantId).param("previewId", previewId).query(Long.class).optional()
                .orElseThrow(() -> notFound("preview_id", previewId));
        if (version != expectedVersion) {
            throw new DomainException("VERSION_CONFLICT", "Invoice preview was modified by another request", 409,
                    Map.of("expected_version", expectedVersion, "current_version", version));
        }
    }

    private void assertVersion(String ifMatch, long bodyVersion) {
        long headerVersion = VersionEtag.parse(ifMatch);
        if (headerVersion != bodyVersion) {
            throw new DomainException("VERSION_CONFLICT",
                    "If-Match and expected_version must identify the same preview version", 409,
                    Map.of("if_match_version", headerVersion, "expected_version", bodyVersion));
        }
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id, Object before,
                        Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    private PreviewHeader mapPreviewHeader(ResultSet rs, int row) throws SQLException {
        return new PreviewHeader(rs.getObject("id", UUID.class), rs.getString("preview_number"),
                rs.getObject("invoice_profile_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("template_version_id", UUID.class),
                rs.getObject("approval_workflow_version_id", UUID.class),
                rs.getObject("period_start", OffsetDateTime.class), rs.getObject("period_end", OffsetDateTime.class),
                rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getString("timezone"), rs.getString("language"), rs.getString("currency_code"),
                rs.getLong("subtotal_minor"), rs.getLong("discount_minor"), rs.getLong("tax_minor"),
                rs.getLong("adjustment_minor"), rs.getLong("total_minor"),
                json(rs.getString("profile_snapshot_json")), json(rs.getString("party_snapshot_json")),
                json(rs.getString("render_model_json")), json(rs.getString("anomaly_json")),
                rs.getString("calculation_hash"), rs.getString("status"), rs.getLong("approval_revision"),
                rs.getObject("generated_at", OffsetDateTime.class), rs.getObject("approved_at", OffsetDateTime.class),
                rs.getObject("finalized_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private PreviewItem mapPreviewItem(ResultSet rs, int row) throws SQLException {
        UUID exclusionId = rs.getObject("exclusion_id", UUID.class);
        return new PreviewItem(rs.getObject("id", UUID.class), rs.getObject("contract_item_id", UUID.class),
                rs.getObject("service_id", UUID.class), rs.getObject("pricing_rule_version_id", UUID.class),
                rs.getObject("usage_snapshot_id", UUID.class), rs.getString("source_key"), rs.getInt("line_no"),
                rs.getString("item_name"), rs.getString("item_description"),
                rs.getObject("billing_period_start", OffsetDateTime.class),
                rs.getObject("billing_period_end", OffsetDateTime.class), rs.getBigDecimal("raw_usage"),
                rs.getBigDecimal("converted_usage"), rs.getBigDecimal("rounded_usage"),
                rs.getBigDecimal("billing_usage"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getBigDecimal("unit_price"), rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
                rs.getLong("tax_minor"), rs.getLong("total_minor"),
                json(rs.getString("calculation_snapshot_json")), json(rs.getString("display_json")),
                exclusionId != null, exclusionId, rs.getString("exclusion_reason"));
    }

    private PreviewAdjustment mapAdjustment(ResultSet rs, int row) throws SQLException {
        return new PreviewAdjustment(rs.getObject("id", UUID.class), rs.getString("adjustment_type"),
                rs.getString("description"), rs.getLong("amount_minor"), rs.getBigDecimal("tax_rate"),
                rs.getBoolean("included_in_tax_base"), rs.getString("reason"), rs.getString("status"),
                rs.getObject("attachment_file_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getObject("approved_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("removed_at", OffsetDateTime.class));
    }

    private ApprovalAction mapApprovalAction(ResultSet rs, int row) throws SQLException {
        UUID actionId = rs.getObject("action_id", UUID.class);
        return new ApprovalAction(rs.getObject("approval_instance_id", UUID.class),
                rs.getString("approval_status"), rs.getLong("approval_revision"),
                rs.getLong("instance_preview_version"), rs.getObject("current_step_no", Integer.class),
                actionId, actionId == null ? null : rs.getString("action"),
                actionId == null ? null : rs.getObject("action_preview_version", Long.class),
                rs.getObject("actor_id", UUID.class), json(rs.getString("actor_snapshot_json")),
                rs.getString("comment"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private PreviewSummary mapPreview(ResultSet rs, int row) throws SQLException {
        return new PreviewSummary(rs.getObject("id", UUID.class), rs.getString("preview_number"),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("period_start", OffsetDateTime.class), rs.getObject("period_end", OffsetDateTime.class),
                rs.getObject("issue_date", LocalDate.class), rs.getObject("due_date", LocalDate.class),
                rs.getString("currency_code"), rs.getLong("total_minor"), rs.getString("status"),
                rs.getLong("approval_revision"), rs.getLong("version"));
    }

    private InvoiceSummary mapInvoice(ResultSet rs, int row) throws SQLException {
        return new InvoiceSummary(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getObject("source_preview_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("issue_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class), rs.getString("currency_code"),
                rs.getLong("total_minor"), rs.getString("document_status"), rs.getString("send_status"),
                rs.getString("payment_status"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version"));
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private DomainException notFound(String field, UUID id) {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found", 404, Map.of(field, id));
    }

    public record VersionedReasonRequest(@PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record AdjustmentRequest(@PositiveOrZero long expectedVersion,
                                    @NotBlank @Pattern(regexp = "SURCHARGE|DISCOUNT|SLA_CREDIT|BALANCE_CREDIT|CARRY_FORWARD|LATE_FEE|PRICE_CORRECTION|INSTALLATION|TEMP_BANDWIDTH|EXCHANGE_RATE|TAX_CORRECTION|CUSTOM") String adjustmentType,
                                    @NotBlank String description, long amountMinor, BigDecimal taxRate,
                                    boolean includedInTaxBase, UUID attachmentFileId, @NotBlank String reason) {
    }

    public record ApprovalRequest(@PositiveOrZero long expectedVersion, @NotBlank String comment) {
    }

    public record FinalizeRequest(@PositiveOrZero long expectedVersion, @NotBlank String confirmationNote) {
    }

    public record JobAccepted(UUID jobId) {
    }

    public record PreviewDetail(PreviewHeader preview, List<PreviewItem> items,
                                List<PreviewAdjustment> adjustments, List<ApprovalAction> approvals) {
    }

    public record PreviewHeader(UUID id, String previewNumber, UUID invoiceProfileId, UUID customerId,
                                UUID companyId, UUID templateId, UUID templateVersionId,
                                UUID approvalWorkflowVersionId, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                LocalDate issueDate, LocalDate dueDate, String timezone, String language,
                                String currencyCode, long subtotalMinor, long discountMinor, long taxMinor,
                                long adjustmentMinor, long totalMinor, JsonNode profileSnapshot, JsonNode partySnapshot,
                                JsonNode renderModel, JsonNode anomalies, String calculationHash, String status,
                                long approvalRevision, OffsetDateTime generatedAt, OffsetDateTime approvedAt,
                                OffsetDateTime finalizedAt, long version) {
    }

    public record PreviewItem(UUID id, UUID contractItemId, UUID serviceId, UUID pricingRuleVersionId,
                              UUID usageSnapshotId, String sourceKey, int lineNo, String itemName,
                              String itemDescription, OffsetDateTime billingPeriodStart,
                              OffsetDateTime billingPeriodEnd, BigDecimal rawUsage, BigDecimal convertedUsage,
                              BigDecimal roundedUsage, BigDecimal billingUsage, BigDecimal quantity, String unit,
                              BigDecimal unitPrice, long subtotalMinor, long discountMinor, long taxMinor,
                              long totalMinor, JsonNode calculationSnapshot, JsonNode display, boolean excluded,
                              UUID exclusionId, String exclusionReason) {
    }

    public record PreviewAdjustment(UUID id, String adjustmentType, String description, long amountMinor,
                                    BigDecimal taxRate, boolean includedInTaxBase, String reason, String status,
                                    UUID attachmentFileId, UUID createdBy, UUID approvedBy,
                                    OffsetDateTime createdAt, OffsetDateTime removedAt) {
    }

    public record ApprovalAction(UUID approvalInstanceId, String approvalStatus, long approvalRevision,
                                 long instancePreviewVersion, Integer currentStepNo, UUID actionId, String action,
                                 Long actionPreviewVersion, UUID actorId, JsonNode actorSnapshot, String comment,
                                 OffsetDateTime createdAt) {
    }

    public record PreviewSummary(UUID id, String previewNumber, UUID customerId, UUID companyId,
                                 OffsetDateTime periodStart, OffsetDateTime periodEnd, LocalDate issueDate,
                                 LocalDate dueDate, String currencyCode, long totalMinor, String status,
                                 long approvalRevision, long version) {
    }

    public record InvoiceSummary(UUID id, String invoiceNumber, UUID sourcePreviewId, UUID customerId,
                                 UUID companyId, LocalDate issueDate, LocalDate dueDate, String currencyCode,
                                 long totalMinor, String documentStatus, String sendStatus, String paymentStatus,
                                 OffsetDateTime createdAt, long version) {
    }
}
