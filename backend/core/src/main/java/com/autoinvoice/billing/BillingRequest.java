package com.autoinvoice.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BillingRequest(
        BillingType billingType,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate activeFrom,
        LocalDate activeTo,
        ProrationMode prorationMode,
        String currency,
        int currencyMinorUnit,
        BigDecimal rawUsage,
        RoundingRule rounding,
        BigDecimal quantity,
        BigDecimal baseFee,
        BigDecimal unitPrice,
        BigDecimal committedQuantity,
        BigDecimal overageUnitPrice,
        BigDecimal freeAllowance,
        BigDecimal minimumCharge,
        BigDecimal maximumCharge,
        BigDecimal discountRate,
        BigDecimal taxRate,
        boolean taxInclusive,
        List<PricingTier> tiers
) {
    public BillingRequest {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
        rounding = rounding == null ? RoundingRule.none() : rounding;
        prorationMode = prorationMode == null ? ProrationMode.NO_PRORATION : prorationMode;
    }
}
