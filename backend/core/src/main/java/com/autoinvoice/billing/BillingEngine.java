package com.autoinvoice.billing;

import com.autoinvoice.platform.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BillingEngine {
    private static final int MAX_DECIMAL_PRECISION = 30;
    private static final int MAX_DECIMAL_SCALE = 12;
    private static final int MAX_DECIMAL_INTEGER_DIGITS = MAX_DECIMAL_PRECISION - MAX_DECIMAL_SCALE;

    public BillingResult calculate(BillingRequest request) {
        validate(request);

        BigDecimal rawUsage = zeroIfNull(request.rawUsage());
        BigDecimal roundedUsage = roundUsage(rawUsage, request.rounding());
        BigDecimal billableUsage = roundedUsage;
        BigDecimal prorationFactor = prorationFactor(request);
        BigDecimal gross;

        switch (request.billingType()) {
            case FIXED_FEE -> gross = required(request.baseFee(), "base_fee").multiply(prorationFactor);
            case QUANTITY -> gross = required(request.quantity(), "quantity")
                    .multiply(required(request.unitPrice(), "unit_price"))
                    .multiply(prorationFactor);
            case COMMITTED_PLUS_OVERAGE -> {
                BigDecimal committed = required(request.committedQuantity(), "committed_quantity");
                BigDecimal overage = roundedUsage.subtract(committed).max(BigDecimal.ZERO);
                gross = required(request.baseFee(), "base_fee").multiply(prorationFactor)
                        .add(overage.multiply(required(request.overageUnitPrice(), "overage_unit_price")));
            }
            case TOTAL_TRAFFIC -> {
                billableUsage = roundedUsage.subtract(zeroIfNull(request.freeAllowance())).max(BigDecimal.ZERO);
                gross = zeroIfNull(request.baseFee()).multiply(prorationFactor)
                        .add(billableUsage.multiply(required(request.unitPrice(), "unit_price")));
            }
            case GRADUATED -> gross = calculateGraduated(roundedUsage, request.tiers());
            case VOLUME -> gross = calculateVolume(roundedUsage, request.tiers());
            default -> throw new DomainException("UNSUPPORTED_BILLING_TYPE", "Unsupported billing type");
        }

        BigDecimal afterCap = BigDecimal.ZERO;
        if (prorationFactor.signum() > 0) {
            BigDecimal afterFloor = request.minimumCharge() == null
                    ? gross
                    : gross.max(request.minimumCharge());
            afterCap = request.maximumCharge() == null
                    ? afterFloor
                    : afterFloor.min(request.maximumCharge());
        }

        BigDecimal discount = afterCap.multiply(zeroIfNull(request.discountRate()));
        BigDecimal afterDiscount = afterCap.subtract(discount);
        BigDecimal taxRate = zeroIfNull(request.taxRate());
        BigDecimal tax;
        BigDecimal total;
        if (request.taxInclusive() && taxRate.signum() > 0) {
            BigDecimal net = afterDiscount.divide(BigDecimal.ONE.add(taxRate), 18, RoundingMode.HALF_UP);
            tax = afterDiscount.subtract(net);
            total = afterDiscount;
        } else {
            tax = afterDiscount.multiply(taxRate);
            total = afterDiscount.add(tax);
        }

        BigDecimal subtotalQuantized = quantize(afterCap, request.currencyMinorUnit());
        BigDecimal discountQuantized = quantize(discount, request.currencyMinorUnit());
        BigDecimal taxQuantized = quantize(tax, request.currencyMinorUnit());
        BigDecimal totalQuantized = quantize(total, request.currencyMinorUnit());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema_version", 1);
        snapshot.put("billing_type", request.billingType().name());
        snapshot.put("period_start", request.periodStart().toString());
        snapshot.put("period_end", request.periodEnd().toString());
        snapshot.put("proration_mode", request.prorationMode().name());
        snapshot.put("proration_factor", prorationFactor.toPlainString());
        snapshot.put("currency", request.currency());
        snapshot.put("raw_usage", rawUsage.toPlainString());
        snapshot.put("rounded_usage", roundedUsage.toPlainString());
        snapshot.put("billable_usage", billableUsage.toPlainString());
        snapshot.put("subtotal", subtotalQuantized.toPlainString());
        snapshot.put("discount", discountQuantized.toPlainString());
        snapshot.put("tax", taxQuantized.toPlainString());
        snapshot.put("total", totalQuantized.toPlainString());

        return new BillingResult(
                rawUsage,
                roundedUsage,
                billableUsage,
                subtotalQuantized,
                discountQuantized,
                taxQuantized,
                totalQuantized,
                toMinor(subtotalQuantized, request.currencyMinorUnit()),
                toMinor(discountQuantized, request.currencyMinorUnit()),
                toMinor(taxQuantized, request.currencyMinorUnit()),
                toMinor(totalQuantized, request.currencyMinorUnit()),
                Map.copyOf(snapshot)
        );
    }

    private void validate(BillingRequest request) {
        if (request == null || request.billingType() == null) {
            throw new DomainException("INVALID_BILLING_REQUEST", "Billing request and billing type are required");
        }
        if (request.periodStart() == null || request.periodEnd() == null
                || !request.periodEnd().isAfter(request.periodStart())) {
            throw new DomainException("INVALID_BILLING_PERIOD", "Billing period must be a non-empty half-open interval");
        }
        if (request.activeFrom() != null && request.activeTo() != null
                && !request.activeTo().isAfter(request.activeFrom())) {
            throw new DomainException("INVALID_ACTIVE_PERIOD", "Active period must be a non-empty half-open interval");
        }
        if (request.currency() == null || !request.currency().matches("[A-Z]{3}")
                || request.currencyMinorUnit() < 0 || request.currencyMinorUnit() > 6) {
            throw new DomainException("INVALID_CURRENCY", "Currency and minor unit are invalid");
        }
        nonNegative(request.rawUsage(), "raw_usage", "NEGATIVE_USAGE", "Usage cannot be negative");
        nonNegative(request.quantity(), "quantity");
        nonNegative(request.baseFee(), "base_fee");
        nonNegative(request.unitPrice(), "unit_price");
        nonNegative(request.committedQuantity(), "committed_quantity");
        nonNegative(request.overageUnitPrice(), "overage_unit_price");
        nonNegative(request.freeAllowance(), "free_allowance");
        nonNegative(request.minimumCharge(), "minimum_charge");
        nonNegative(request.maximumCharge(), "maximum_charge");
        if (request.discountRate() != null
                && (request.discountRate().signum() < 0 || request.discountRate().compareTo(BigDecimal.ONE) > 0)) {
            throw new DomainException("INVALID_DISCOUNT_RATE", "Discount rate must be between 0 and 1");
        }
        validateRate(request.discountRate(), "discount_rate");
        if (request.taxRate() != null
                && (request.taxRate().signum() < 0 || request.taxRate().compareTo(BigDecimal.ONE) > 0)) {
            throw new DomainException("INVALID_TAX_RATE", "Tax rate must be between 0 and 1");
        }
        validateRate(request.taxRate(), "tax_rate");
        if (request.minimumCharge() != null && request.maximumCharge() != null
                && request.maximumCharge().compareTo(request.minimumCharge()) < 0) {
            throw new DomainException("INVALID_CHARGE_LIMITS", "Maximum charge cannot be lower than minimum charge");
        }
        validateRounding(request.rounding());
        validateTiers(request.tiers());
    }

    private void validateRounding(RoundingRule rounding) {
        if (rounding == null || rounding.mode() == null) {
            throw new DomainException("INVALID_ROUNDING_RULE", "Rounding mode is required");
        }
        if (rounding.mode() == UsageRoundingMode.DECIMAL_SCALE
                && (rounding.scale() == null || rounding.scale() < 0 || rounding.scale() > MAX_DECIMAL_SCALE)) {
            throw new DomainException("INVALID_ROUNDING_SCALE", "Decimal rounding scale must be between 0 and 12");
        }
        if (rounding.mode() == UsageRoundingMode.CEIL_STEP) {
            nonNegative(rounding.step(), "rounding_step");
            if (rounding.step() == null || rounding.step().signum() == 0) {
                throw new DomainException("INVALID_ROUNDING_STEP", "Rounding step must be positive");
            }
        }
    }

    private void validateTiers(List<PricingTier> tiers) {
        BigDecimal expectedLower = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            PricingTier tier = tiers.get(i);
            if (tier == null || tier.lowerBound() == null || tier.unitPrice() == null) {
                throw new DomainException("INVALID_PRICING_TIERS", "Pricing tier bounds and price are required");
            }
            nonNegative(tier.lowerBound(), "tier_lower_bound");
            nonNegative(tier.upperBound(), "tier_upper_bound");
            nonNegative(tier.unitPrice(), "tier_unit_price");
            if (tier.lowerBound().compareTo(expectedLower) != 0) {
                throw new DomainException("INVALID_PRICING_TIERS", "Pricing tiers must be contiguous and start at zero");
            }
            if (i < tiers.size() - 1 && tier.upperBound() == null) {
                throw new DomainException("INVALID_PRICING_TIERS", "Only the last pricing tier may be open ended");
            }
            if (i == tiers.size() - 1 && tier.upperBound() != null) {
                throw new DomainException("INVALID_PRICING_TIERS",
                        "The last pricing tier must be open ended so every usage level is covered");
            }
            expectedLower = tier.upperBound();
        }
    }

    private BigDecimal prorationFactor(BillingRequest request) {
        LocalDate activeStart = request.activeFrom() == null
                ? request.periodStart()
                : request.activeFrom().isAfter(request.periodStart()) ? request.activeFrom() : request.periodStart();
        LocalDate activeEnd = request.activeTo() == null
                ? request.periodEnd()
                : request.activeTo().isBefore(request.periodEnd()) ? request.activeTo() : request.periodEnd();
        if (!activeEnd.isAfter(activeStart)) {
            return BigDecimal.ZERO;
        }
        if (request.prorationMode() == ProrationMode.NO_PRORATION
                || request.prorationMode() == ProrationMode.FULL_MONTH_IF_ACTIVE) {
            return BigDecimal.ONE;
        }
        long activeDays = Math.max(0, ChronoUnit.DAYS.between(activeStart, activeEnd));
        long denominator = request.prorationMode() == ProrationMode.THIRTY_DAYS
                ? 30
                : ChronoUnit.DAYS.between(request.periodStart(), request.periodEnd());
        return BigDecimal.valueOf(activeDays)
                .divide(BigDecimal.valueOf(denominator), 18, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE);
    }

    private BigDecimal roundUsage(BigDecimal rawUsage, RoundingRule rule) {
        return switch (rule.mode()) {
            case NONE -> rawUsage;
            case DECIMAL_SCALE -> rawUsage.setScale(rule.scale() == null ? 0 : rule.scale(), RoundingMode.HALF_UP);
            case HALF_UP_INTEGER -> rawUsage.setScale(0, RoundingMode.HALF_UP);
            case CEIL_INTEGER -> rawUsage.setScale(0, RoundingMode.CEILING);
            case CEIL_STEP -> {
                BigDecimal step = required(rule.step(), "rounding_step");
                if (step.signum() <= 0) {
                    throw new DomainException("INVALID_ROUNDING_STEP", "Rounding step must be positive");
                }
                yield rawUsage.divide(step, 0, RoundingMode.CEILING).multiply(step);
            }
        };
    }

    private BigDecimal calculateGraduated(BigDecimal usage, List<PricingTier> tiers) {
        if (tiers.isEmpty()) {
            throw new DomainException("MISSING_PRICING_TIERS", "Graduated pricing requires tiers");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PricingTier tier : tiers) {
            if (usage.compareTo(tier.lowerBound()) <= 0) {
                break;
            }
            BigDecimal upper = tier.upperBound() == null ? usage : usage.min(tier.upperBound());
            BigDecimal quantity = upper.subtract(tier.lowerBound()).max(BigDecimal.ZERO);
            total = total.add(quantity.multiply(tier.unitPrice()));
        }
        return total;
    }

    private BigDecimal calculateVolume(BigDecimal usage, List<PricingTier> tiers) {
        if (tiers.isEmpty()) {
            throw new DomainException("MISSING_PRICING_TIERS", "Volume pricing requires tiers");
        }
        return tiers.stream()
                .filter(tier -> usage.compareTo(tier.lowerBound()) >= 0)
                .filter(tier -> tier.upperBound() == null || usage.compareTo(tier.upperBound()) < 0)
                .findFirst()
                .map(tier -> usage.multiply(tier.unitPrice()))
                .orElseThrow(() -> new DomainException("PRICING_TIER_NOT_FOUND", "No pricing tier covers the usage"));
    }

    private BigDecimal quantize(BigDecimal value, int minorUnit) {
        return value.setScale(minorUnit, RoundingMode.HALF_UP);
    }

    private long toMinor(BigDecimal value, int minorUnit) {
        try {
            return value.movePointRight(minorUnit).longValueExact();
        } catch (ArithmeticException exception) {
            throw new DomainException("AMOUNT_OUT_OF_RANGE",
                    "Calculated amount cannot be represented in the supported minor-unit range");
        }
    }

    private BigDecimal required(BigDecimal value, String field) {
        if (value == null) {
            throw new DomainException("MISSING_BILLING_PARAMETER", "Missing billing parameter: " + field);
        }
        return value;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void nonNegative(BigDecimal value, String field) {
        nonNegative(value, field, "INVALID_BILLING_PARAMETER", "Billing parameter cannot be negative: " + field);
    }

    private void nonNegative(BigDecimal value, String field, String code, String message) {
        if (value == null) {
            return;
        }
        validateDecimal(value, field);
        if (value.signum() < 0) {
            throw new DomainException(code, message);
        }
    }

    private void validateRate(BigDecimal value, String field) {
        if (value == null) {
            return;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        int scale = Math.max(0, normalized.scale());
        if (normalized.precision() > 12 || scale > 8) {
            throw new DomainException("INVALID_BILLING_PARAMETER",
                    "Billing rate exceeds numeric(12,8): " + field);
        }
    }

    private void validateDecimal(BigDecimal value, String field) {
        BigDecimal normalized = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        int scale = Math.max(0, normalized.scale());
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (normalized.precision() > MAX_DECIMAL_PRECISION || scale > MAX_DECIMAL_SCALE
                || integerDigits > MAX_DECIMAL_INTEGER_DIGITS) {
            throw new DomainException("INVALID_BILLING_PARAMETER",
                    "Billing parameter exceeds numeric(30,12): " + field);
        }
    }
}
