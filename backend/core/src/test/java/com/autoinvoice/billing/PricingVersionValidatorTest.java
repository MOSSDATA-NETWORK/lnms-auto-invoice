package com.autoinvoice.billing;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingVersionValidatorTest {
    private final PricingVersionValidator validator = new PricingVersionValidator();

    @Test
    void acceptsCommittedPlusOverageOnlyWithDedicatedOverageRate() {
        assertThatCode(() -> validator.validate(definition("COMMITTED_PLUS_OVERAGE",
                new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("12"), List.of())))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(definition("COMMITTED_PLUS_OVERAGE",
                new BigDecimal("1000"), new BigDecimal("100"), null, List.of())))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("overage_unit_price");
    }

    @Test
    void rejectsTierGaps() {
        assertThatThrownBy(() -> validator.validate(definition("GRADUATED", null, null, null,
                List.of(new PricingTier(BigDecimal.ONE, null, BigDecimal.TEN)))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("contiguous");
    }

    @Test
    void rejectsNegativeOptionalChargeLimits() {
        PricingVersionValidator.PricingVersionDefinition definition = new PricingVersionValidator.PricingVersionDefinition(
                "QUANTITY", OffsetDateTime.parse("2026-07-01T00:00:00Z"), null,
                BigDecimal.ONE, null, null, null, new BigDecimal("-0.01"), null,
                BigDecimal.ZERO, BigDecimal.ZERO, "NONE", null, null, List.of());

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("minimum_charge");
    }

    @Test
    void rejectsUnsupportedOrUnboundedRoundingConfiguration() {
        PricingVersionValidator.PricingVersionDefinition unsupported = new PricingVersionValidator.PricingVersionDefinition(
                "QUANTITY", OffsetDateTime.parse("2026-07-01T00:00:00Z"), null,
                BigDecimal.ONE, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, "DANGEROUS", null, null, List.of());
        PricingVersionValidator.PricingVersionDefinition unbounded = new PricingVersionValidator.PricingVersionDefinition(
                "QUANTITY", OffsetDateTime.parse("2026-07-01T00:00:00Z"), null,
                BigDecimal.ONE, null, null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, "DECIMAL_SCALE", 100_000, null, List.of());

        assertThatThrownBy(() -> validator.validate(unsupported))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Unsupported rounding mode");
        assertThatThrownBy(() -> validator.validate(unbounded))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("between 0 and 12");
    }

    private PricingVersionValidator.PricingVersionDefinition definition(
            String type, BigDecimal base, BigDecimal committed, BigDecimal overage, List<PricingTier> tiers) {
        return new PricingVersionValidator.PricingVersionDefinition(type, OffsetDateTime.parse("2026-07-01T00:00:00Z"),
                null, type.equals("QUANTITY") ? BigDecimal.ONE : null, base, committed, overage,
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, "NONE", null, null, tiers);
    }
}
