package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentRefundTest {

    private static Payment captured() {
        return Payment.captured("ABC234", "ch_1", Money.of("200.00", "USD"), Instant.parse("2026-11-01T10:00:00Z"));
    }

    @Test
    void should_owe_a_refund_after_a_cancellation_that_qualifies() {
        Payment payment = captured();

        payment.refundDue();

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUND_DUE);
    }

    @Test
    void should_record_that_a_late_cancellation_gets_nothing_back() {
        Payment payment = captured();

        payment.notRefundable();

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.NO_REFUND);
    }

    @Test
    void should_be_refunded_once_the_provider_confirms() {
        Payment payment = captured();
        payment.refundDue();

        payment.refunded();

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void should_not_mark_refunded_before_a_refund_was_owed() {
        Payment payment = captured();

        assertThatIllegalStateException().isThrownBy(payment::refunded);
    }

    @Test
    void should_not_decide_the_refund_twice() {
        Payment payment = captured();
        payment.notRefundable();

        assertThatIllegalStateException().isThrownBy(payment::refundDue);
    }

    @Test
    void should_keep_the_original_amount_whatever_the_refund_status() {
        Payment payment = captured();
        payment.refundDue();

        assertThat(payment.getAmount()).isEqualTo(Money.of("200.00", "USD"));
    }
}
