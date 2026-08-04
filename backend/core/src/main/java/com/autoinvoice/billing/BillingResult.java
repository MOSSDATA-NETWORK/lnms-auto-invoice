package com.autoinvoice.billing;

import java.math.BigDecimal;
import java.util.Map;

public record BillingResult(
        BigDecimal rawUsage,
        BigDecimal roundedUsage,
        BigDecimal billableUsage,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal tax,
        BigDecimal total,
        long subtotalMinor,
        long discountMinor,
        long taxMinor,
        long totalMinor,
        Map<String, Object> calculationSnapshot
) {
}

