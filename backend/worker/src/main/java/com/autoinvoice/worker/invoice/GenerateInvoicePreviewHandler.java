package com.autoinvoice.worker.invoice;

import com.autoinvoice.invoice.InvoicePreviewGenerationService;
import com.autoinvoice.invoice.InvoicePreviewWorkflowService;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.worker.jobs.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class GenerateInvoicePreviewHandler implements JobHandler {
    public static final String TYPE = "GENERATE_INVOICE_PREVIEW";
    private final InvoicePreviewGenerationService generationService;
    private final InvoicePreviewWorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public GenerateInvoicePreviewHandler(InvoicePreviewGenerationService generationService,
                                         InvoicePreviewWorkflowService workflowService,
                                         ObjectMapper objectMapper) {
        this.generationService = generationService;
        this.workflowService = workflowService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) {
        JsonNode payload = job.payload();
        UUID requestedBy = requiredUuid(payload, "requested_by");
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
        return result;
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
