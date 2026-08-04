package com.autoinvoice.invoice;

import com.autoinvoice.platform.DomainException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AssignmentValidator {
    public void validate(List<ProfileAssignment> assignments, Instant periodStart, Instant periodEnd,
                         BigDecimal sourceAmount) {
        List<ProfileAssignment> active = assignments.stream()
                .filter(assignment -> assignment.overlaps(periodStart, periodEnd))
                .toList();

        long chargeCount = active.stream().filter(a -> a.mode() == AssignmentMode.CHARGE).count();
        if (chargeCount > 1) {
            throw new DomainException("DUPLICATE_BILLING_ASSIGNMENT",
                    "A contract item cannot be charged by multiple active invoice profiles", 409,
                    java.util.Map.of("charge_assignments", chargeCount));
        }

        List<ProfileAssignment> percentages = active.stream()
                .filter(a -> a.mode() == AssignmentMode.ALLOCATE_PERCENT)
                .toList();
        if (!percentages.isEmpty()) {
            BigDecimal sum = percentages.stream()
                    .map(ProfileAssignment::allocationValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(new BigDecimal("100")) != 0) {
                throw new DomainException("INVALID_PERCENT_ALLOCATION",
                        "Percentage allocations must total exactly 100%", 422,
                        java.util.Map.of("allocation_total", sum.toPlainString()));
            }
        }

        if (sourceAmount != null) {
            BigDecimal fixed = active.stream()
                    .filter(a -> a.mode() == AssignmentMode.ALLOCATE_FIXED)
                    .map(ProfileAssignment::allocationValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (fixed.compareTo(sourceAmount) > 0) {
                throw new DomainException("FIXED_ALLOCATION_EXCEEDS_SOURCE",
                        "Fixed allocations cannot exceed the source amount");
            }
        }
    }
}

