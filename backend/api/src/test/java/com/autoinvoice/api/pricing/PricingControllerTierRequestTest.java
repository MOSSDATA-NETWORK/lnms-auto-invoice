package com.autoinvoice.api.pricing;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingControllerTierRequestTest {
    @Test
    void reportsInvalidTierBoundsAsAStableDomainValidationError() {
        PricingController.TierRequest request = new PricingController.TierRequest(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);

        assertThatThrownBy(request::domain)
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo("PRICING_VERSION_INVALID");
                    org.assertj.core.api.Assertions.assertThat(exception.status()).isEqualTo(422);
                });
    }
}
