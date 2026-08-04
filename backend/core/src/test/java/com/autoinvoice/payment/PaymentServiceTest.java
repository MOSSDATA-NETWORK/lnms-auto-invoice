package com.autoinvoice.payment;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest {

    @Test
    void reversingAllocationRestoresOnlyTheUnrefundedBalanceForAllocation() {
        PaymentService.PaymentBalance beforeReversal = PaymentService.paymentBalance(100, 60, 40);
        PaymentService.PaymentBalance afterReversal = PaymentService.paymentBalance(100, 0, 40);

        assertThat(beforeReversal.availableMinor()).isZero();
        assertThat(afterReversal.availableMinor()).isEqualTo(60);
        assertThat(afterReversal.status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(afterReversal.allocatable()).isTrue();
    }

    @Test
    void derivesAllocationStatusFromAllocatedAndRefundedBalances() {
        assertThat(PaymentService.paymentBalance(100, 0, 0).status()).isEqualTo("CONFIRMED");
        assertThat(PaymentService.paymentBalance(100, 40, 0).status()).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(PaymentService.paymentBalance(100, 100, 0).status()).isEqualTo("ALLOCATED");
        assertThat(PaymentService.paymentBalance(100, 0, 40).status()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(PaymentService.paymentBalance(100, 0, 100).status()).isEqualTo("REFUNDED");
    }

    @Test
    void rejectsAnyBalanceWhoseAllocationsAndConfirmedRefundsExceedThePayment() {
        assertThatThrownBy(() -> PaymentService.paymentBalance(100, 61, 40))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PAYMENT_BALANCE_INVARIANT_BROKEN");
                    assertThat(exception.details()).containsEntry("amount_minor", "100")
                            .containsEntry("allocated_minor", "61")
                            .containsEntry("refunded_minor", "40");
                });
    }

    @Test
    void calculatesAtTheBigintBoundaryWithoutOverflow() {
        PaymentService.PaymentBalance balance = PaymentService.paymentBalance(
                Long.MAX_VALUE, Long.MAX_VALUE - 1, 1);

        assertThat(balance.availableMinor()).isZero();
    }
}
