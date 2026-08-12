package com.autoinvoice.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingEngineTest {
    private final BillingEngine engine = new BillingEngine();

    @Test
    void calculatesCommittedBandwidthWithCeilingStep() {
        BillingResult result = engine.calculate(request(
                BillingType.COMMITTED_PLUS_OVERAGE,
                new BigDecimal("176.32"),
                new RoundingRule(UsageRoundingMode.CEIL_STEP, null, new BigDecimal("10")),
                new BigDecimal("2000"),
                new BigDecimal("15"),
                new BigDecimal("100"),
                List.of()
        ));

        assertThat(result.roundedUsage()).isEqualByComparingTo("180");
        assertThat(result.total()).isEqualByComparingTo("3200.00");
        assertThat(result.totalMinor()).isEqualTo(320000L);
    }

    @Test
    void aggregateUsageMustAlreadyBeResolvedByTheUsageAdapter() {
        BillingResult result = engine.calculate(request(
                BillingType.QUANTITY,
                BigDecimal.ZERO,
                RoundingRule.none(),
                null,
                new BigDecimal("12.50"),
                null,
                List.of()
        ));

        assertThat(result.total()).isEqualByComparingTo("12.50");
    }

    @Test
    void calculatesGraduatedTiersAtBoundary() {
        BillingRequest request = request(
                BillingType.GRADUATED,
                new BigDecimal("150"),
                RoundingRule.none(),
                null,
                null,
                null,
                List.of(
                        new PricingTier(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("2")),
                        new PricingTier(new BigDecimal("100"), null, BigDecimal.ONE)
                )
        );

        assertThat(engine.calculate(request).total()).isEqualByComparingTo("250.00");
    }

    @Test
    void proratesUsingHalfOpenActualDays() {
        BillingRequest request = new BillingRequest(
                BillingType.FIXED_FEE,
                LocalDate.of(2028, 2, 1),
                LocalDate.of(2028, 3, 1),
                LocalDate.of(2028, 2, 15),
                null,
                ProrationMode.ACTUAL_DAYS,
                "CNY",
                2,
                null,
                RoundingRule.none(),
                null,
                new BigDecimal("2900"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                false,
                List.of()
        );

        assertThat(engine.calculate(request).total()).isEqualByComparingTo("1500.00");
    }

    @Test
    void doesNotChargeAnInactivePeriodForFullMonthModes() {
        for (ProrationMode mode : List.of(ProrationMode.NO_PRORATION, ProrationMode.FULL_MONTH_IF_ACTIVE)) {
            for (LocalDate[] activePeriod : List.of(
                    new LocalDate[]{LocalDate.of(2026, 8, 1), null},
                    new LocalDate[]{LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)})) {
                BillingRequest request = new BillingRequest(
                        BillingType.FIXED_FEE,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 8, 1),
                        activePeriod[0],
                        activePeriod[1],
                        mode,
                        "CNY",
                        2,
                        null,
                        RoundingRule.none(),
                        null,
                        new BigDecimal("1000"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        BigDecimal.ZERO,
                        false,
                        List.of()
                );

                assertThat(engine.calculate(request).total())
                        .as("%s %s", mode.name(), activePeriod[0])
                        .isEqualByComparingTo("0.00");
            }
        }
    }

    @Test
    void rejectsNegativeUsage() {
        assertThatThrownBy(() -> engine.calculate(request(
                BillingType.QUANTITY,
                new BigDecimal("-1"),
                RoundingRule.none(),
                null,
                BigDecimal.ONE,
                null,
                List.of()
        ))).hasMessageContaining("Usage cannot be negative");
    }

    @Test
    void rejectsNegativePricingInputsEvenWhenTheyAreOptionalForTheBillingType() {
        BillingRequest request = request(
                BillingType.TOTAL_TRAFFIC,
                new BigDecimal("10"),
                RoundingRule.none(),
                new BigDecimal("-1"),
                BigDecimal.ONE,
                null,
                List.of()
        );

        assertThatThrownBy(() -> engine.calculate(request))
                .hasMessageContaining("base_fee");
    }

    @Test
    void rejectsUnboundedDecimalRoundingScale() {
        BillingRequest request = request(
                BillingType.QUANTITY,
                BigDecimal.ZERO,
                new RoundingRule(UsageRoundingMode.DECIMAL_SCALE, 100_000, null),
                null,
                BigDecimal.ONE,
                null,
                List.of()
        );

        assertThatThrownBy(() -> engine.calculate(request))
                .hasMessageContaining("scale must be between 0 and 12");
    }

    @Test
    void reportsAmountsThatOverflowMinorUnitsAsADomainError() {
        BillingRequest baseRequest = request(
                BillingType.QUANTITY,
                BigDecimal.ZERO,
                RoundingRule.none(),
                null,
                new BigDecimal("999999999999999999"),
                null,
                List.of()
        );
        BillingRequest request = new BillingRequest(
                baseRequest.billingType(), baseRequest.periodStart(), baseRequest.periodEnd(), baseRequest.activeFrom(),
                baseRequest.activeTo(), baseRequest.prorationMode(), baseRequest.currency(), baseRequest.currencyMinorUnit(),
                baseRequest.rawUsage(), baseRequest.rounding(), new BigDecimal("999999999999999999"),
                baseRequest.baseFee(), baseRequest.unitPrice(), baseRequest.committedQuantity(),
                baseRequest.overageUnitPrice(), baseRequest.freeAllowance(), baseRequest.minimumCharge(),
                baseRequest.maximumCharge(), baseRequest.discountRate(), baseRequest.taxRate(),
                baseRequest.taxInclusive(), baseRequest.tiers());

        assertThatThrownBy(() -> engine.calculate(request))
                .isInstanceOf(com.autoinvoice.platform.DomainException.class)
                .hasMessageContaining("minor-unit range");
    }

    @Test
    void chargesNothingForUsageBasedTypesWhenPeriodHasNoOverlap() {
        BillingRequest committed = inactivePeriodRequest(
                BillingType.COMMITTED_PLUS_OVERAGE, new BigDecimal("180"), new BigDecimal("2000"),
                null, new BigDecimal("100"), List.of());
        assertThat(engine.calculate(committed).total()).isEqualByComparingTo("0.00");

        BillingRequest traffic = inactivePeriodRequest(
                BillingType.TOTAL_TRAFFIC, new BigDecimal("500"), new BigDecimal("300"),
                new BigDecimal("2"), null, List.of());
        assertThat(engine.calculate(traffic).total()).isEqualByComparingTo("0.00");

        BillingRequest graduated = inactivePeriodRequest(
                BillingType.GRADUATED, new BigDecimal("500"), null, null, null,
                List.of(new PricingTier(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("2")),
                        new PricingTier(new BigDecimal("100"), null, BigDecimal.ONE)));
        assertThat(engine.calculate(graduated).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void doesNotApplyMinimumChargeWhenPeriodHasNoOverlap() {
        BillingRequest request = inactivePeriodRequest(
                BillingType.FIXED_FEE, null, new BigDecimal("1000"), null, null, List.of());

        assertThat(engine.calculate(request).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void capsThirtyDayProrationAtFullMonth() {
        BillingRequest request = new BillingRequest(
                BillingType.FIXED_FEE,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1),
                null,
                null,
                ProrationMode.THIRTY_DAYS,
                "CNY",
                2,
                null,
                RoundingRule.none(),
                null,
                new BigDecimal("3000"),
                null,
                null,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                List.of()
        );

        assertThat(engine.calculate(request).total()).isEqualByComparingTo("3000.00");
    }

    @Test
    void rejectsTiersWithBoundedLastTier() {
        BillingRequest request = request(
                BillingType.GRADUATED,
                new BigDecimal("500"),
                RoundingRule.none(),
                null,
                null,
                null,
                List.of(
                        new PricingTier(BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("2")),
                        new PricingTier(new BigDecimal("100"), new BigDecimal("200"), BigDecimal.ONE)
                )
        );

        assertThatThrownBy(() -> engine.calculate(request))
                .hasMessageContaining("last pricing tier must be open ended");
    }

    private BillingRequest inactivePeriodRequest(BillingType type, BigDecimal usage, BigDecimal baseFee,
                                                 BigDecimal unitPrice, BigDecimal committed,
                                                 List<PricingTier> tiers) {
        return new BillingRequest(
                type,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 6, 1),
                ProrationMode.ACTUAL_DAYS,
                "CNY",
                2,
                usage,
                RoundingRule.none(),
                BigDecimal.ONE,
                baseFee,
                unitPrice,
                committed,
                new BigDecimal("15"),
                BigDecimal.ZERO,
                new BigDecimal("500"),
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                tiers
        );
    }

    private BillingRequest request(BillingType type, BigDecimal usage, RoundingRule rounding,
                                   BigDecimal baseFee, BigDecimal unitPrice, BigDecimal committed,
                                   List<PricingTier> tiers) {
        return new BillingRequest(
                type,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 1),
                null,
                null,
                ProrationMode.NO_PRORATION,
                "CNY",
                2,
                usage,
                rounding,
                BigDecimal.ONE,
                baseFee,
                unitPrice,
                committed,
                type == BillingType.COMMITTED_PLUS_OVERAGE ? unitPrice : null,
                BigDecimal.ZERO,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                false,
                tiers
        );
    }
}
