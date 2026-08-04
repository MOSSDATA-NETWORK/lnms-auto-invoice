package com.autoinvoice.billing;

import com.autoinvoice.platform.DomainException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class PricingVersionValidator {
    private static final int MAX_DECIMAL_PRECISION = 30;
    private static final int MAX_DECIMAL_SCALE = 12;
    private static final int MAX_DECIMAL_INTEGER_DIGITS = MAX_DECIMAL_PRECISION - MAX_DECIMAL_SCALE;

    public void validate(PricingVersionDefinition definition) {
        if (definition == null) {
            throw invalid("Pricing version definition is required", Map.of());
        }
        BillingType type;
        try {
            type = BillingType.valueOf(definition.billingType());
        } catch (RuntimeException exception) {
            throw invalid("Unsupported billing type",
                    Map.of("billing_type", String.valueOf(definition.billingType())));
        }
        if (definition.effectiveFrom() == null
                || definition.effectiveTo() != null && !definition.effectiveTo().isAfter(definition.effectiveFrom())) {
            throw invalid("Price version effective period must be a non-empty half-open interval", Map.of());
        }
        range(definition.discountRate(), "discount_rate");
        range(definition.taxRate(), "tax_rate");
        nonNegative(definition.unitPrice(), "unit_price");
        nonNegative(definition.baseFee(), "base_fee");
        nonNegative(definition.committedQuantity(), "committed_quantity");
        nonNegative(definition.overageUnitPrice(), "overage_unit_price");
        nonNegative(definition.minimumCharge(), "minimum_charge");
        nonNegative(definition.maximumCharge(), "maximum_charge");
        if (definition.minimumCharge() != null && definition.maximumCharge() != null
                && definition.maximumCharge().compareTo(definition.minimumCharge()) < 0) {
            throw invalid("Maximum charge cannot be lower than minimum charge", Map.of());
        }
        UsageRoundingMode roundingMode;
        try {
            roundingMode = UsageRoundingMode.valueOf(definition.roundingMode());
        } catch (RuntimeException exception) {
            throw invalid("Unsupported rounding mode",
                    Map.of("rounding_mode", String.valueOf(definition.roundingMode())));
        }
        if (roundingMode == UsageRoundingMode.DECIMAL_SCALE
                && (definition.roundingScale() == null
                || definition.roundingScale() < 0 || definition.roundingScale() > MAX_DECIMAL_SCALE)) {
            throw invalid("DECIMAL_SCALE requires rounding_scale between 0 and 12", Map.of());
        }
        if (definition.roundingScale() != null
                && (definition.roundingScale() < 0 || definition.roundingScale() > MAX_DECIMAL_SCALE)) {
            throw invalid("rounding_scale must be between 0 and 12", Map.of());
        }
        if (roundingMode == UsageRoundingMode.CEIL_STEP
                && (definition.roundingStep() == null || definition.roundingStep().signum() <= 0)) {
            throw invalid("CEIL_STEP rounding requires a positive rounding_step", Map.of());
        }
        nonNegative(definition.roundingStep(), "rounding_step");
        switch (type) {
            case FIXED_FEE -> required(definition.baseFee(), "base_fee");
            case QUANTITY -> required(definition.unitPrice(), "unit_price");
            case COMMITTED_PLUS_OVERAGE -> {
                required(definition.baseFee(), "base_fee");
                required(definition.committedQuantity(), "committed_quantity");
                required(definition.overageUnitPrice(), "overage_unit_price");
            }
            case TOTAL_TRAFFIC -> required(definition.unitPrice(), "unit_price");
            case GRADUATED, VOLUME -> validateTiers(definition.tiers(), type);
        }
    }

    private void validateTiers(List<PricingTier> tiers, BillingType type) {
        if (tiers == null || tiers.isEmpty()) {
            throw invalid(type + " pricing requires at least one tier", Map.of());
        }
        BigDecimal expected = BigDecimal.ZERO;
        for (int index = 0; index < tiers.size(); index++) {
            PricingTier tier = tiers.get(index);
            if (tier.lowerBound() == null || tier.unitPrice() == null || tier.unitPrice().signum() < 0
                    || tier.lowerBound().compareTo(expected) != 0) {
                throw invalid("Pricing tiers must be contiguous, non-negative and start at zero",
                        Map.of("tier", index + 1));
            }
            if (index < tiers.size() - 1 && tier.upperBound() == null) {
                throw invalid("Only the last pricing tier may be open ended", Map.of("tier", index + 1));
            }
            if (tier.upperBound() != null && tier.upperBound().compareTo(tier.lowerBound()) <= 0) {
                throw invalid("Pricing tier upper bound must exceed its lower bound", Map.of("tier", index + 1));
            }
            nonNegative(tier.lowerBound(), "tier_lower_bound");
            nonNegative(tier.upperBound(), "tier_upper_bound");
            nonNegative(tier.unitPrice(), "tier_unit_price");
            expected = tier.upperBound();
        }
    }

    private void range(BigDecimal value, String field) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
            throw invalid(field + " must be between 0 and 1", Map.of("field", field));
        }
        if (value != null) {
            BigDecimal normalized = value.stripTrailingZeros();
            if (normalized.precision() > 12 || Math.max(0, normalized.scale()) > 8) {
                throw invalid(field + " exceeds numeric(12,8)", Map.of("field", field));
            }
        }
    }

    private void required(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw invalid("Missing or negative pricing parameter: " + field, Map.of("field", field));
        }
    }

    private void nonNegative(BigDecimal value, String field) {
        if (value == null) {
            return;
        }
        BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        int scale = Math.max(0, normalized.scale());
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (value.signum() < 0 || normalized.precision() > MAX_DECIMAL_PRECISION
                || scale > MAX_DECIMAL_SCALE || integerDigits > MAX_DECIMAL_INTEGER_DIGITS) {
            throw invalid(field + " must be non-negative and fit numeric(30,12)", Map.of("field", field));
        }
    }

    private DomainException invalid(String message, Map<String, Object> details) {
        return new DomainException("PRICING_VERSION_INVALID", message, 422, details);
    }

    public record PricingVersionDefinition(String billingType, OffsetDateTime effectiveFrom,
                                           OffsetDateTime effectiveTo, BigDecimal unitPrice,
                                           BigDecimal baseFee, BigDecimal committedQuantity,
                                           BigDecimal overageUnitPrice, BigDecimal minimumCharge,
                                           BigDecimal maximumCharge, BigDecimal discountRate,
                                           BigDecimal taxRate, String roundingMode,
                                           Integer roundingScale, BigDecimal roundingStep,
                                           List<PricingTier> tiers) {
        public PricingVersionDefinition {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }
}
