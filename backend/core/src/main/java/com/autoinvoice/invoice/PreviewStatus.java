package com.autoinvoice.invoice;

import com.autoinvoice.platform.DomainException;

import java.util.EnumSet;
import java.util.Set;

public enum PreviewStatus {
    GENERATING,
    DRAFT,
    BUSINESS_REVIEW,
    FINANCE_REVIEW,
    APPROVED,
    FINALIZING,
    FINALIZED,
    REJECTED,
    ERROR,
    VOIDED;

    public void requireTransitionTo(PreviewStatus target) {
        if (!allowedTargets().contains(target)) {
            throw new DomainException("INVALID_PREVIEW_TRANSITION",
                    "Preview cannot transition from " + this + " to " + target, 409,
                    java.util.Map.of("from", name(), "to", target.name()));
        }
    }

    private Set<PreviewStatus> allowedTargets() {
        return switch (this) {
            case GENERATING -> EnumSet.of(DRAFT, ERROR, VOIDED);
            case DRAFT -> EnumSet.of(GENERATING, BUSINESS_REVIEW, VOIDED);
            case BUSINESS_REVIEW -> EnumSet.of(FINANCE_REVIEW, REJECTED, DRAFT);
            case FINANCE_REVIEW -> EnumSet.of(APPROVED, REJECTED, DRAFT);
            case APPROVED -> EnumSet.of(FINALIZING, DRAFT);
            case FINALIZING -> EnumSet.of(FINALIZED, ERROR);
            case ERROR -> EnumSet.of(GENERATING, FINALIZING, VOIDED);
            case REJECTED -> EnumSet.of(DRAFT, VOIDED);
            case FINALIZED, VOIDED -> EnumSet.noneOf(PreviewStatus.class);
        };
    }
}

