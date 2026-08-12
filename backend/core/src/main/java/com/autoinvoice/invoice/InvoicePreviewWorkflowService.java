package com.autoinvoice.invoice;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.storage.FileReferencePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InvoicePreviewWorkflowService {
    private static final Set<String> NON_EDITABLE = Set.of("FINALIZING", "FINALIZED", "VOIDED");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final FileReferencePolicy fileReferencePolicy;

    public InvoicePreviewWorkflowService(JdbcClient jdbc, ObjectMapper objectMapper,
                                         FileReferencePolicy fileReferencePolicy) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fileReferencePolicy = fileReferencePolicy;
    }

    @Transactional
    public PreviewResult addAdjustment(UUID tenantId, UUID previewId, long expectedVersion, UUID actorId,
                                       Set<String> actorPermissions,
                                       String type, String description, long amountMinor, BigDecimal taxRate,
                                       boolean includedInTaxBase, String reason, UUID attachmentFileId) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        requireEditable(preview);
        if (attachmentFileId != null) {
            fileReferencePolicy.requireAssociable(
                    tenantId, attachmentFileId, actorId, actorPermissions);
        }
        UUID adjustmentId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO invoice_preview_adjustments(
                            id, tenant_id, invoice_preview_id, adjustment_type, description, amount_minor,
                            tax_rate, included_in_tax_base, reason, attachment_file_id, created_by
                        ) VALUES (
                            :id, :tenantId, :previewId, :type, :description, :amount,
                            :taxRate, :included, :reason, :attachmentId, :actorId
                        )
                        """)
                .param("id", adjustmentId).param("tenantId", tenantId).param("previewId", previewId)
                .param("type", type).param("description", description).param("amount", amountMinor)
                .param("taxRate", taxRate).param("included", includedInTaxBase).param("reason", reason)
                .param("attachmentId", attachmentFileId).param("actorId", actorId).update();
        return contentChanged(preview, actorId, "Adjustment added", adjustmentId);
    }

    @Transactional
    public PreviewResult removeAdjustment(UUID tenantId, UUID previewId, UUID adjustmentId,
                                          long expectedVersion, UUID actorId, String reason) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        requireEditable(preview);
        int changed = jdbc.sql("""
                        UPDATE invoice_preview_adjustments
                        SET status = 'REMOVED', removed_at = now(), reason = reason || E'\nRemoved: ' || :reason
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                          AND id = :adjustmentId AND status = 'ACTIVE'
                        """)
                .param("reason", reason).param("tenantId", tenantId).param("previewId", previewId)
                .param("adjustmentId", adjustmentId).update();
        if (changed != 1) {
            throw new DomainException("RESOURCE_NOT_FOUND", "Active preview adjustment was not found", 404,
                    Map.of("adjustment_id", adjustmentId));
        }
        return contentChanged(preview, actorId, "Adjustment removed: " + reason, adjustmentId);
    }

    @Transactional
    public PreviewResult excludeItem(UUID tenantId, UUID previewId, UUID itemId, long expectedVersion,
                                     UUID actorId, String reason) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        requireEditable(preview);
        requireItem(tenantId, previewId, itemId);
        UUID exclusionId = UuidV7.generate();
        int inserted = jdbc.sql("""
                        INSERT INTO invoice_preview_exclusions(
                            id, tenant_id, invoice_preview_id, invoice_preview_item_id, reason, created_by
                        ) VALUES (:id, :tenantId, :previewId, :itemId, :reason, :actorId)
                        ON CONFLICT (invoice_preview_id, invoice_preview_item_id) DO NOTHING
                        """)
                .param("id", exclusionId).param("tenantId", tenantId).param("previewId", previewId)
                .param("itemId", itemId).param("reason", reason).param("actorId", actorId).update();
        if (inserted == 0) {
            return result(preview, null);
        }
        return contentChanged(preview, actorId, "System line excluded: " + reason, exclusionId);
    }

    @Transactional
    public PreviewResult includeItem(UUID tenantId, UUID previewId, UUID itemId, long expectedVersion,
                                     UUID actorId, String reason) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        requireEditable(preview);
        int deleted = jdbc.sql("""
                        DELETE FROM invoice_preview_exclusions
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                          AND invoice_preview_item_id = :itemId
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).param("itemId", itemId).update();
        if (deleted != 1) {
            throw new DomainException("RESOURCE_NOT_FOUND", "Preview exclusion was not found", 404,
                    Map.of("invoice_preview_item_id", itemId));
        }
        return contentChanged(preview, actorId, "System line restored: " + reason, itemId);
    }

    @Transactional
    public ApprovalResult submit(UUID tenantId, UUID previewId, long expectedVersion, UUID actorId,
                                 String actorName, String comment) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        if (!Set.of("DRAFT", "REJECTED").contains(preview.status())) {
            throw invalidState(preview, "Only a draft or rejected preview can be submitted");
        }
        if (preview.anomalyCount() > 0) {
            throw new DomainException("BLOCKING_PREVIEW_ANOMALY",
                    "Blocking preview anomalies must be resolved before review", 422,
                    Map.of("anomaly_count", preview.anomalyCount()));
        }
        ApprovalStep first = firstStep(tenantId, preview.workflowVersionId());
        long revision = Math.addExact(preview.approvalRevision(), 1);
        UUID approvalId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO approval_instances(
                            id, tenant_id, invoice_preview_id, workflow_version_id, preview_version,
                            approval_revision, status, current_step_no, requested_by
                        ) VALUES (
                            :id, :tenantId, :previewId, :workflowVersionId, :previewVersion,
                            :revision, 'PENDING', :stepNo, :actorId
                        )
                        """)
                .param("id", approvalId).param("tenantId", tenantId).param("previewId", previewId)
                .param("workflowVersionId", preview.workflowVersionId()).param("previewVersion", preview.version())
                .param("revision", revision).param("stepNo", first.stepNo()).param("actorId", actorId).update();
        insertAction(tenantId, approvalId, null, preview.version(), "SUBMIT", actorId, actorName, comment);
        String status = statusFor(first.permissionCode());
        jdbc.sql("""
                        UPDATE invoice_previews SET status = :status, approval_revision = :revision,
                            approved_at = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :previewId AND version = :version
                        """)
                .param("status", status).param("revision", revision).param("tenantId", tenantId)
                .param("previewId", previewId).param("version", preview.version()).update();
        return new ApprovalResult(previewId, approvalId, status, first.stepNo(), revision, preview.version());
    }

    @Transactional
    public ApprovalResult approve(UUID tenantId, UUID previewId, long expectedVersion, UUID actorId,
                                  String actorName, String requiredPermission, String comment) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        CurrentApproval approval = currentApproval(tenantId, preview);
        if (!requiredPermission.equals(approval.permissionCode())) {
            throw new DomainException("APPROVAL_STEP_MISMATCH", "The current approval step requires another permission", 409,
                    Map.of("required_permission", approval.permissionCode()));
        }
        String expectedStatus = statusFor(requiredPermission);
        if (!expectedStatus.equals(preview.status())) {
            throw invalidState(preview, "Preview is not waiting for this approval step");
        }
        insertAction(tenantId, approval.id(), approval.stepId(), preview.version(), "APPROVE",
                actorId, actorName, comment);
        ApprovalStep next = nextStep(tenantId, approval.workflowVersionId(), approval.stepNo());
        if (next == null) {
            jdbc.sql("""
                            UPDATE approval_instances SET status = 'APPROVED', current_step_no = NULL,
                                completed_at = now() WHERE tenant_id = :tenantId AND id = :approvalId
                            """)
                    .param("tenantId", tenantId).param("approvalId", approval.id()).update();
            jdbc.sql("""
                            UPDATE invoice_previews SET status = 'APPROVED', approved_at = now(), updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :previewId AND version = :version
                            """)
                    .param("tenantId", tenantId).param("previewId", previewId)
                    .param("version", preview.version()).update();
            return new ApprovalResult(previewId, approval.id(), "APPROVED", null,
                    preview.approvalRevision(), preview.version());
        }
        String nextStatus = statusFor(next.permissionCode());
        jdbc.sql("""
                        UPDATE approval_instances SET current_step_no = :stepNo
                        WHERE tenant_id = :tenantId AND id = :approvalId AND status = 'PENDING'
                        """)
                .param("stepNo", next.stepNo()).param("tenantId", tenantId).param("approvalId", approval.id()).update();
        jdbc.sql("""
                        UPDATE invoice_previews SET status = :status, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :previewId AND version = :version
                        """)
                .param("status", nextStatus).param("tenantId", tenantId).param("previewId", previewId)
                .param("version", preview.version()).update();
        return new ApprovalResult(previewId, approval.id(), nextStatus, next.stepNo(),
                preview.approvalRevision(), preview.version());
    }

    @Transactional
    public ApprovalResult reject(UUID tenantId, UUID previewId, long expectedVersion, UUID actorId,
                                 String actorName, String comment) {
        Preview preview = lockPreview(tenantId, previewId, expectedVersion);
        if (!Set.of("BUSINESS_REVIEW", "FINANCE_REVIEW").contains(preview.status())) {
            throw invalidState(preview, "Only a preview under review can be rejected");
        }
        CurrentApproval approval = currentApproval(tenantId, preview);
        insertAction(tenantId, approval.id(), approval.stepId(), preview.version(), "REJECT",
                actorId, actorName, comment);
        jdbc.sql("""
                        UPDATE approval_instances SET status = 'REJECTED', completed_at = now()
                        WHERE tenant_id = :tenantId AND id = :approvalId AND status = 'PENDING'
                        """)
                .param("tenantId", tenantId).param("approvalId", approval.id()).update();
        jdbc.sql("""
                        UPDATE invoice_previews SET status = 'REJECTED', approved_at = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :previewId AND version = :version
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).param("version", preview.version()).update();
        return new ApprovalResult(previewId, approval.id(), "REJECTED", null,
                preview.approvalRevision(), preview.version());
    }

    private PreviewResult contentChanged(Preview preview, UUID actorId, String reason, UUID resourceId) {
        invalidateApprovals(preview, actorId, reason);
        Totals totals = calculateTotals(preview.tenantId(), preview.id());
        ObjectNode renderModel = renderModel(preview, totals);
        String hash = sha256(renderModel.toString());
        int changed = jdbc.sql("""
                        UPDATE invoice_previews
                        SET subtotal_minor = :subtotal, discount_minor = :discount, tax_minor = :tax,
                            adjustment_minor = :adjustment, total_minor = :total,
                            render_model_json = CAST(:renderModel AS jsonb), calculation_hash = :hash,
                            status = 'DRAFT', approved_at = NULL, updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :previewId AND version = :version
                        """)
                .param("subtotal", totals.subtotalMinor()).param("discount", totals.discountMinor())
                .param("tax", totals.taxMinor()).param("adjustment", totals.adjustmentMinor())
                .param("total", totals.totalMinor()).param("renderModel", renderModel.toString()).param("hash", hash)
                .param("tenantId", preview.tenantId()).param("previewId", preview.id())
                .param("version", preview.version()).update();
        if (changed != 1) {
            throw versionConflict(preview.version());
        }
        return new PreviewResult(preview.id(), "DRAFT", preview.approvalRevision(), preview.version() + 1,
                totals, resourceId);
    }

    private Totals calculateTotals(UUID tenantId, UUID previewId) {
        LineTotals lines = jdbc.sql("""
                        SELECT COALESCE(SUM(item.subtotal_minor), 0) AS subtotal,
                               COALESCE(SUM(item.discount_minor), 0) AS discount,
                               COALESCE(SUM(item.tax_minor), 0) AS tax
                        FROM invoice_preview_items item
                        LEFT JOIN invoice_preview_exclusions exclusion
                          ON exclusion.tenant_id = item.tenant_id
                         AND exclusion.invoice_preview_item_id = item.id
                        WHERE item.tenant_id = :tenantId AND item.invoice_preview_id = :previewId
                          AND exclusion.id IS NULL
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query((rs, row) -> new LineTotals(rs.getLong("subtotal"), rs.getLong("discount"), rs.getLong("tax")))
                .single();
        List<Adjustment> adjustments = adjustments(tenantId, previewId);
        long adjustmentTotal = 0;
        long tax = lines.taxMinor();
        for (Adjustment adjustment : adjustments) {
            adjustmentTotal = Math.addExact(adjustmentTotal, adjustment.amountMinor());
            if (adjustment.includedInTaxBase() && adjustment.taxRate() != null) {
                tax = Math.addExact(tax, BigDecimal.valueOf(adjustment.amountMinor())
                        .multiply(adjustment.taxRate()).setScale(0, RoundingMode.HALF_UP).longValueExact());
            }
        }
        long total = Math.addExact(Math.addExact(Math.subtractExact(lines.subtotalMinor(), lines.discountMinor()), tax),
                adjustmentTotal);
        if (total < 0) {
            throw new DomainException("NEGATIVE_INVOICE_TOTAL", "Preview total cannot be negative", 422,
                    Map.of("total_minor", total));
        }
        return new Totals(lines.subtotalMinor(), lines.discountMinor(), tax, adjustmentTotal, total);
    }

    private ObjectNode renderModel(Preview preview, Totals totals) {
        ObjectNode model = (ObjectNode) readJson(preview.renderModel()).deepCopy();
        Set<UUID> excluded = jdbc.sql("""
                        SELECT invoice_preview_item_id FROM invoice_preview_exclusions
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                        """)
                .param("tenantId", preview.tenantId()).param("previewId", preview.id())
                .query(UUID.class).list().stream().collect(java.util.stream.Collectors.toSet());
        Map<String, UUID> itemIds = jdbc.sql("""
                        SELECT id, source_key FROM invoice_preview_items
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                        """)
                .param("tenantId", preview.tenantId()).param("previewId", preview.id())
                .query((rs, row) -> Map.entry(rs.getString("source_key"), rs.getObject("id", UUID.class))).list()
                .stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        JsonNode items = model.path("items");
        if (items.isArray()) {
            items.forEach(item -> {
                if (item instanceof ObjectNode object) {
                    UUID itemId = itemIds.get(object.path("sourceKey").asText());
                    object.put("excluded", itemId != null && excluded.contains(itemId));
                }
            });
        }
        ArrayNode adjustmentNodes = model.putArray("adjustments");
        adjustments(preview.tenantId(), preview.id())
                .forEach(value -> adjustmentNodes.add(objectMapper.valueToTree(value)));
        model.set("totals", objectMapper.valueToTree(totals));
        return model;
    }

    private List<Adjustment> adjustments(UUID tenantId, UUID previewId) {
        return jdbc.sql("""
                        SELECT id, adjustment_type, description, amount_minor, tax_rate,
                               included_in_tax_base, reason, attachment_file_id, created_by
                        FROM invoice_preview_adjustments
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId AND status = 'ACTIVE'
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("previewId", previewId)
                .query((rs, row) -> new Adjustment(rs.getObject("id", UUID.class), rs.getString("adjustment_type"),
                        rs.getString("description"), rs.getLong("amount_minor"), rs.getBigDecimal("tax_rate"),
                        rs.getBoolean("included_in_tax_base"), rs.getString("reason"),
                        rs.getObject("attachment_file_id", UUID.class), rs.getObject("created_by", UUID.class))).list();
    }

    private void invalidateApprovals(Preview preview, UUID actorId, String reason) {
        List<UUID> approvals = jdbc.sql("""
                        SELECT id FROM approval_instances
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId
                          AND approval_revision = :revision AND status IN ('PENDING', 'APPROVED')
                        FOR UPDATE
                        """)
                .param("tenantId", preview.tenantId()).param("previewId", preview.id())
                .param("revision", preview.approvalRevision()).query(UUID.class).list();
        for (UUID approvalId : approvals) {
            jdbc.sql("""
                            UPDATE approval_instances SET status = 'INVALIDATED', completed_at = now(),
                                invalidation_reason = :reason
                            WHERE tenant_id = :tenantId AND id = :approvalId
                            """)
                    .param("reason", reason).param("tenantId", preview.tenantId())
                    .param("approvalId", approvalId).update();
            insertAction(preview.tenantId(), approvalId, null, preview.version(), "INVALIDATE",
                    actorId, null, reason);
        }
    }

    private CurrentApproval currentApproval(UUID tenantId, Preview preview) {
        CurrentApproval result = jdbc.sql("""
                        SELECT instance.id, instance.workflow_version_id, instance.current_step_no,
                               instance.preview_version, step.id AS step_id, step.permission_code
                        FROM approval_instances instance
                        JOIN approval_steps step ON step.tenant_id = instance.tenant_id
                             AND step.workflow_version_id = instance.workflow_version_id
                             AND step.step_no = instance.current_step_no
                        WHERE instance.tenant_id = :tenantId AND instance.invoice_preview_id = :previewId
                          AND instance.approval_revision = :revision AND instance.status = 'PENDING'
                        FOR UPDATE OF instance
                        """)
                .param("tenantId", tenantId).param("previewId", preview.id())
                .param("revision", preview.approvalRevision()).query((rs, row) -> new CurrentApproval(
                        rs.getObject("id", UUID.class), rs.getObject("workflow_version_id", UUID.class),
                        rs.getInt("current_step_no"), rs.getLong("preview_version"),
                        rs.getObject("step_id", UUID.class), rs.getString("permission_code"))).optional()
                .orElseThrow(() -> new DomainException("APPROVAL_NOT_PENDING", "No current approval is pending", 409,
                        Map.of("preview_id", preview.id())));
        if (result.previewVersion() != preview.version()) {
            throw new DomainException("APPROVAL_STALE", "Approval does not match the current preview version", 409,
                    Map.of("approval_version", result.previewVersion(), "preview_version", preview.version()));
        }
        return result;
    }

    private ApprovalStep firstStep(UUID tenantId, UUID workflowVersionId) {
        return jdbc.sql("""
                        SELECT id, step_no, permission_code FROM approval_steps
                        WHERE tenant_id = :tenantId AND workflow_version_id = :workflowVersionId
                        ORDER BY step_no LIMIT 1
                        """)
                .param("tenantId", tenantId).param("workflowVersionId", workflowVersionId)
                .query(this::mapStep).optional().orElseThrow(() -> new DomainException("APPROVAL_WORKFLOW_EMPTY",
                        "Published approval workflow has no steps", 422, Map.of("workflow_version_id", workflowVersionId)));
    }

    private ApprovalStep nextStep(UUID tenantId, UUID workflowVersionId, int currentStep) {
        return jdbc.sql("""
                        SELECT id, step_no, permission_code FROM approval_steps
                        WHERE tenant_id = :tenantId AND workflow_version_id = :workflowVersionId
                          AND step_no > :currentStep ORDER BY step_no LIMIT 1
                        """)
                .param("tenantId", tenantId).param("workflowVersionId", workflowVersionId)
                .param("currentStep", currentStep).query(this::mapStep).optional().orElse(null);
    }

    private ApprovalStep mapStep(ResultSet rs, int row) throws SQLException {
        return new ApprovalStep(rs.getObject("id", UUID.class), rs.getInt("step_no"),
                rs.getString("permission_code"));
    }

    private void insertAction(UUID tenantId, UUID approvalId, UUID stepId, long previewVersion,
                              String action, UUID actorId, String actorName, String comment) {
        ObjectNode actor = objectMapper.createObjectNode();
        if (actorId != null) {
            actor.put("user_id", actorId.toString());
        }
        if (actorName != null) {
            actor.put("display_name", actorName);
        }
        jdbc.sql("""
                        INSERT INTO approval_actions(
                            id, tenant_id, approval_instance_id, approval_step_id, preview_version,
                            action, actor_id, actor_snapshot_json, comment
                        ) VALUES (
                            :id, :tenantId, :approvalId, :stepId, :previewVersion,
                            :action, :actorId, CAST(:actor AS jsonb), :comment
                        )
                        """)
                .param("id", UuidV7.generate()).param("tenantId", tenantId).param("approvalId", approvalId)
                .param("stepId", stepId).param("previewVersion", previewVersion).param("action", action)
                .param("actorId", actorId).param("actor", actor.toString()).param("comment", comment).update();
    }

    private Preview lockPreview(UUID tenantId, UUID previewId, long expectedVersion) {
        Preview preview = jdbc.sql("""
                        SELECT id, tenant_id, approval_workflow_version_id, status, approval_revision, version,
                               (SELECT count(*)::int FROM jsonb_array_elements(anomaly_json) AS anomaly
                                WHERE COALESCE((anomaly ->> 'blocking')::boolean, TRUE)) AS anomaly_count,
                               render_model_json
                        FROM invoice_previews
                        WHERE tenant_id = :tenantId AND id = :previewId FOR UPDATE
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query((rs, row) -> new Preview(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("approval_workflow_version_id", UUID.class), rs.getString("status"),
                        rs.getLong("approval_revision"), rs.getLong("version"), rs.getInt("anomaly_count"),
                        rs.getString("render_model_json"))).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice preview was not found", 404,
                        Map.of("preview_id", previewId)));
        if (preview.version() != expectedVersion) {
            throw versionConflict(expectedVersion);
        }
        return preview;
    }

    private void requireEditable(Preview preview) {
        if (NON_EDITABLE.contains(preview.status())) {
            throw invalidState(preview, "Preview can no longer be modified");
        }
    }

    private void requireItem(UUID tenantId, UUID previewId, UUID itemId) {
        boolean exists = jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM invoice_preview_items
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId AND id = :itemId)
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).param("itemId", itemId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new DomainException("RESOURCE_NOT_FOUND", "Preview item was not found", 404,
                    Map.of("invoice_preview_item_id", itemId));
        }
    }

    private String statusFor(String permissionCode) {
        if ("preview.approve.business".equals(permissionCode)) {
            return "BUSINESS_REVIEW";
        }
        if ("preview.approve.finance".equals(permissionCode)) {
            return "FINANCE_REVIEW";
        }
        throw new DomainException("APPROVAL_PERMISSION_UNSUPPORTED",
                "Approval step permission is not supported by the MVP workflow", 422,
                Map.of("permission_code", permissionCode));
    }

    private PreviewResult result(Preview preview, UUID resourceId) {
        Totals totals = calculateTotals(preview.tenantId(), preview.id());
        return new PreviewResult(preview.id(), preview.status(), preview.approvalRevision(), preview.version(),
                totals, resourceId);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted preview render model is invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private DomainException invalidState(Preview preview, String message) {
        return new DomainException("INVALID_PREVIEW_TRANSITION", message, 409,
                Map.of("preview_id", preview.id(), "status", preview.status()));
    }

    private DomainException versionConflict(long expectedVersion) {
        return new DomainException("VERSION_CONFLICT", "Invoice preview was modified by another request", 409,
                Map.of("expected_version", expectedVersion));
    }

    public record PreviewResult(UUID previewId, String status, long approvalRevision, long version,
                                Totals totals, UUID resourceId) {
    }

    public record ApprovalResult(UUID previewId, UUID approvalInstanceId, String status,
                                 Integer currentStepNo, long approvalRevision, long version) {
    }

    public record Totals(long subtotalMinor, long discountMinor, long taxMinor,
                         long adjustmentMinor, long totalMinor) {
    }

    private record Preview(UUID id, UUID tenantId, UUID workflowVersionId, String status,
                           long approvalRevision, long version, int anomalyCount, String renderModel) {
    }

    private record ApprovalStep(UUID id, int stepNo, String permissionCode) {
    }

    private record CurrentApproval(UUID id, UUID workflowVersionId, int stepNo, long previewVersion,
                                   UUID stepId, String permissionCode) {
    }

    private record LineTotals(long subtotalMinor, long discountMinor, long taxMinor) {
    }

    private record Adjustment(UUID id, String type, String description, long amountMinor, BigDecimal taxRate,
                              boolean includedInTaxBase, String reason, UUID attachmentFileId, UUID createdBy) {
    }
}
