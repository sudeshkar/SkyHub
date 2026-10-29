package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void should_keep_two_decimal_places_so_equal_amounts_are_equal() {
        assertThat(Money.of("310", "USD")).isEqualTo(Money.of("310.00", "USD"));
        assertThat(Money.of(new BigDecimal("310.0"), "USD").amount()).hasToString("310.00");
    }

    @Test
    void should_add_and_multiply_without_floating_point_error() {
        Money total = Money.of("0.10", "USD").plus(Money.of("0.20", "USD"));

        assertThat(total).isEqualTo(Money.of("0.30", "USD"));
        assertThat(Money.of("310.55", "USD").times(3)).isEqualTo(Money.of("931.65", "USD"));
    }

    @Test
    void should_never_mix_currencies() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> Money.of("10", "USD").plus(Money.of("10", "AED")))
                .withMessageContaining("currenc");
    }

    @Test
    void should_refuse_a_negative_amount_or_an_odd_currency_code() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> Money.of("-1", "USD"));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> Money.of("1", "usd"));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> Money.of("1", "US"));
    }

    @Test
    void should_round_half_up_to_the_cent() {
        assertThat(Money.of("1.005", "USD").amount()).hasToString("1.01");
    }
}
