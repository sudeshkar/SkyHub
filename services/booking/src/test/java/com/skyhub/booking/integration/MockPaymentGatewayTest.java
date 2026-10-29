package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.application.PaymentGateway.Charge;
import com.skyhub.booking.application.PaymentUnavailableException;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PaymentDeclinedException;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {

    private static final Money AMOUNT = Money.of("200.00", "USD");
    private final MockPaymentGateway gateway = new MockPaymentGateway();

    @Test
    void should_approve_the_test_visa() {
        Charge charge = gateway.charge("tok_visa", AMOUNT, "key-1");

        assertThat(charge.id()).startsWith("ch_");
        assertThat(charge.amount()).isEqualTo(AMOUNT);
    }

    @Test
    void should_return_the_same_charge_for_the_same_key_and_never_charge_twice() {
        Charge first = gateway.charge("tok_visa", AMOUNT, "key-1");
        Charge again = gateway.charge("tok_visa", AMOUNT, "key-1");
        Charge other = gateway.charge("tok_visa", AMOUNT, "key-2");

        assertThat(again).isEqualTo(first);
        assertThat(other.id()).isNotEqualTo(first.id());
        assertThat(gateway.chargeCount()).isEqualTo(2);
    }

    @Test
    void should_decline_the_declined_token_and_unknown_tokens() {
        assertThatExceptionOfType(PaymentDeclinedException.class).isThrownBy(() -> gateway.charge("tok_declined", AMOUNT, "k"))
                .withMessageContaining("declined");
        assertThatExceptionOfType(PaymentDeclinedException.class).isThrownBy(() -> gateway.charge("tok_nonsense", AMOUNT, "k"));
        assertThat(gateway.chargeCount()).isZero();
    }

    @Test
    void should_behave_like_an_outage_for_the_outage_token() {
        assertThatExceptionOfType(PaymentUnavailableException.class)
                .isThrownBy(() -> gateway.charge("tok_provider_down", AMOUNT, "k"));
        assertThat(gateway.chargeCount()).isZero();
    }

    @Test
    void should_refund_a_charge_and_allow_repeating_it() {
        Charge charge = gateway.charge("tok_visa", AMOUNT, "key-1");

        gateway.refund(charge.id());
        gateway.refund(charge.id());

        assertThat(gateway.isRefunded(charge.id())).isTrue();
    }

    @Test
    void should_make_a_fresh_charge_when_a_key_is_used_again_after_its_charge_was_refunded() {
        Charge first = gateway.charge("tok_visa", AMOUNT, "key-1");
        gateway.refund(first.id());

        Charge retry = gateway.charge("tok_visa", AMOUNT, "key-1");

        // Handing back the refunded charge would let a retried booking go through unpaid.
        assertThat(retry.id()).isNotEqualTo(first.id());
        assertThat(gateway.isRefunded(retry.id())).isFalse();
    }

    @Test
    void should_count_refunds() {
        Charge charge = gateway.charge("tok_visa", AMOUNT, "key-1");
        gateway.refund(charge.id());
        gateway.refund(charge.id());

        assertThat(gateway.refundCount()).isEqualTo(1);
    }

    @Test
    void should_not_refund_a_charge_it_never_made() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> gateway.refund("ch_unknown"));
    }
}
