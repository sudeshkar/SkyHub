package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContactEmailTest {

    @ParameterizedTest
    @CsvSource({
            "priya@example.com, p****@example.com",
            "a@example.com, ****@example.com"
    })
    void should_mask_the_local_part(String email, String masked) {
        assertThat(new ContactEmail(email).masked()).isEqualTo(masked);
    }

    @Test
    void should_never_print_the_raw_address() {
        assertThat(new ContactEmail("priya@example.com")).hasToString("p****@example.com");
    }

    @Test
    void should_trim_surrounding_spaces() {
        assertThat(new ContactEmail("  priya@example.com ").value()).isEqualTo("priya@example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-email", "a@b", "two@@example.com", "space in@example.com"})
    void should_reject_invalid_addresses(String email) {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> new ContactEmail(email));
    }

    @Test
    void should_reject_overlong_addresses() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> new ContactEmail("a".repeat(250) + "@example.com"));
    }
}
