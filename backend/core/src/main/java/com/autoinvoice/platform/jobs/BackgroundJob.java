package com.autoinvoice.platform.jobs;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record BackgroundJob(
        UUID id,
        UUID tenantId,
        String type,
        String uniqueKey,
        JsonNode payload,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        Instant leasedUntil
) {
}

