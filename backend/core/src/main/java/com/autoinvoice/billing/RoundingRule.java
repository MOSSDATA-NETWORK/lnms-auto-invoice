package com.autoinvoice.billing;

import java.math.BigDecimal;

public record RoundingRule(UsageRoundingMode mode, Integer scale, BigDecimal step) {
    public static RoundingRule none() {
        return new RoundingRule(UsageRoundingMode.NONE, null, null);
    }
}

