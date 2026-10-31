package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.domain.InvalidBookingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BookingFilterTest {

    @Test
    void should_read_the_three_filters_ignoring_case_and_spaces() {
        assertThat(BookingFilter.fromWire("all")).isEqualTo(BookingFilter.ALL);
        assertThat(BookingFilter.fromWire("upcoming")).isEqualTo(BookingFilter.UPCOMING);
        assertThat(BookingFilter.fromWire(" Past ")).isEqualTo(BookingFilter.PAST);
    }

    @Test
    void should_treat_no_filter_as_all_bookings() {
        assertThat(BookingFilter.fromWire(null)).isEqualTo(BookingFilter.ALL);
        assertThat(BookingFilter.fromWire("")).isEqualTo(BookingFilter.ALL);
        assertThat(BookingFilter.fromWire("  ")).isEqualTo(BookingFilter.ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "cancelled", "upcoming;DROP TABLE pnr", "1", "UPCOMING,PAST"})
    void should_refuse_anything_else_with_a_message_that_says_what_is_allowed(String unknown) {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> BookingFilter.fromWire(unknown))
                .withMessageContaining("all").withMessageContaining("upcoming").withMessageContaining("past");
    }
}
