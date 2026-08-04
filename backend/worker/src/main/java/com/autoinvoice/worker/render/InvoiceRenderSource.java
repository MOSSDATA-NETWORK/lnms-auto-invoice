package com.autoinvoice.worker.render;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public record InvoiceRenderSource(
        UUID tenantId,
        UUID invoiceId,
        String invoiceNumber,
        UUID templateVersionId,
        UUID finalizedBy,
        String dataSnapshotHash,
        String documentStatus,
        JsonNode renderModel,
        String html,
        String css
) {
}
