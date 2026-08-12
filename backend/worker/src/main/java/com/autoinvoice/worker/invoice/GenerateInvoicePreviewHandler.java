package com.autoinvoice.worker.invoice;

import com.autoinvoice.invoice.InvoicePreviewGenerationService;
import com.autoinvoice.invoice.InvoicePreviewWorkflowService;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.worker.jobs.JobHandler;
import com.autoinvoice.worker.librenms.LibrenmsHistorySyncHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class GenerateInvoicePreviewHandler implements JobHandler {
    public static final String TYPE = "GENERATE_INVOICE_PREVIEW";
    private final InvoicePreviewGenerationService generationService;
    private final InvoicePreviewWorkflowService workflowService;
    private final LibrenmsHistorySyncHandler historySync;
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public GenerateInvoicePreviewHandler(InvoicePreviewGenerationService generationService,
                                         InvoicePreviewWorkflowService workflowService,
                                         LibrenmsHistorySyncHandler historySync,
                                         JdbcClient jdbc, ObjectMapper objectMapper) {
        this.generationService = generationService;
        this.workflowService = workflowService;
        this.historySync = historySync;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        JsonNode payload = job.payload();
        UUID requestedBy = requiredUuid(payload, "requested_by");
        int syncedMappings = 0;
        InvoicePreviewGenerationService.GenerationResult generated;
        if (payload.hasNonNull("preview_id")) {
            UUID previewId = requiredUuid(payload, "preview_id");
            long expectedVersion = payload.path("expected_version").asLong(-1);
            if (expectedVersion < 0) {
                throw new IllegalArgumentException("Recalculation payload requires expected_version");
            }
            generated = generationService.recalculate(job.tenantId(), previewId, expectedVersion, requestedBy);
        } else {
            UUID profileId = requiredUuid(payload, "invoice_profile_id");
            OffsetDateTime periodStart = requiredDateTime(payload, "period_start");
            OffsetDateTime periodEnd = requiredDateTime(payload, "period_end");
            if (payload.path("force_usage_sync").asBoolean(false)) {
                syncedMappings = syncUsageSnapshots(job, profileId, periodStart, periodEnd, requestedBy);
            }
            generated = generationService.generate(job.tenantId(), profileId, periodStart, periodEnd,
                    requestedBy, job.id());
        }
        String status = generated.status();
        UUID approvalId = null;
        if (generated.autoSubmitReview() && "DRAFT".equals(generated.status())) {
            var approval = workflowService.submit(job.tenantId(), generated.previewId(), generated.version(),
                    requestedBy, "自动提交", "账单配置启用了自动提交审核");
            status = approval.status();
            approvalId = approval.approvalInstanceId();
        }
        ObjectNode result = objectMapper.createObjectNode()
                .put("resource_type", "invoice_preview")
                .put("resource_id", generated.previewId().toString())
                .put("preview_number", generated.previewNumber())
                .put("status", status)
                .put("version", generated.version());
        if (approvalId != null) {
            result.put("approval_instance_id", approvalId.toString());
        }
        if (syncedMappings > 0) {
            result.put("usage_synced_mappings", syncedMappings);
        }
        return result;
    }

    private int syncUsageSnapshots(BackgroundJob job, UUID profileId, OffsetDateTime periodStart,
                                   OffsetDateTime periodEnd, UUID requestedBy) throws Exception {
        List<UUID> mappingIds = jdbc.sql("""
                        SELECT DISTINCT mapping.id
                        FROM librenms_bill_mappings mapping
                        JOIN contract_items contract_item ON contract_item.tenant_id = mapping.tenant_id
                             AND contract_item.id = mapping.contract_item_id
                        JOIN invoice_profile_assignments assignment ON assignment.tenant_id = mapping.tenant_id
                             AND assignment.contract_item_id = contract_item.id
                        WHERE mapping.tenant_id = :tenantId AND assignment.invoice_profile_id = :profileId
                          AND assignment.status = 'ACTIVE'
                          AND assignment.effective_from < :periodEnd
                          AND (assignment.effective_to IS NULL OR assignment.effective_to > :periodStart)
                          AND mapping.status = 'ACTIVE' AND mapping.discovery_status = 'CONFIRMED'
                          AND mapping.effective_from <= :periodStart
                          AND (mapping.effective_to IS NULL OR mapping.effective_to >= :periodEnd)
                          AND contract_item.billing_type NOT IN ('FIXED_FEE', 'QUANTITY')
                        ORDER BY mapping.id
                        """)
                .param("tenantId", job.tenantId()).param("profileId", profileId)
                .param("periodStart", periodStart).param("periodEnd", periodEnd)
                .query(UUID.class).list();
        for (UUID mappingId : mappingIds) {
            historySync.sync(job.tenantId(), mappingId, periodStart, periodEnd, requestedBy, job.id());
        }
        return mappingIds.size();
    }

    private UUID requiredUuid(JsonNode payload, String field) {
        try {
            return UUID.fromString(payload.path(field).asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Preview job payload requires " + field, exception);
        }
    }

    private OffsetDateTime requiredDateTime(JsonNode payload, String field) {
        try {
            return OffsetDateTime.parse(payload.path(field).asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Preview job payload requires RFC 3339 " + field, exception);
        }
    }
}
