package com.autoinvoice.invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProfileAssignment(
        UUID profileId,
        UUID contractItemId,
        AssignmentMode mode,
        BigDecimal allocationValue,
        Instant effectiveFrom,
        Instant effectiveTo
) {
    public boolean overlaps(Instant start, Instant end) {
        return effectiveFrom.isBefore(end) && (effectiveTo == null || effectiveTo.isAfter(start));
    }
}

