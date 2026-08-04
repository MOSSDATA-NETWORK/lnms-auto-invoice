package com.autoinvoice.billing;

import java.math.BigDecimal;

public record PricingTier(BigDecimal lowerBound, BigDecimal upperBound, BigDecimal unitPrice) {
    public PricingTier {
        if (lowerBound == null || unitPrice == null || lowerBound.signum() < 0 || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Tier bounds and price must be non-negative");
        }
        if (upperBound != null && upperBound.compareTo(lowerBound) <= 0) {
            throw new IllegalArgumentException("Tier upper bound must be greater than lower bound");
        }
    }
}

