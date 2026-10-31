package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.domain.InvalidBookingException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BookingCursorTest {

    private static final Instant MADE = Instant.parse("2026-10-31T10:15:30.123456Z");

    @Test
    void should_carry_a_position_through_encode_and_decode_exactly_to_the_microsecond() {
        BookingCursor cursor = new BookingCursor(MADE, "ABC234");

        assertThat(BookingCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void should_be_safe_to_put_in_an_address() {
        assertThat(new BookingCursor(MADE, "ABC234").encode()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void should_treat_no_cursor_as_the_first_page() {
        assertThat(BookingCursor.decodeOrNull(null)).isNull();
        assertThat(BookingCursor.decodeOrNull("")).isNull();
        assertThat(BookingCursor.decodeOrNull("  ")).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not base64 !!", "bm90LWEtY3Vyc29y"})
    void should_refuse_a_cursor_it_did_not_make(String garbage) {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> BookingCursor.decode(garbage))
                .withMessageContaining("page");
    }

    @Test
    void should_refuse_a_cursor_longer_than_any_it_would_make() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> BookingCursor.decode("x".repeat(300)));
    }

    @Test
    void should_refuse_a_cursor_whose_parts_are_not_a_time_and_a_booking_reference() {
        for (String forged : new String[] {"yesterday|ABC234", "2026-10-31T10:15:30Z|abc", "2026-10-31T10:15:30Z|ABC234|extra",
                "2026-10-31T10:15:30Z|'; DROP TABLE pnr;--", "|"}) {
            String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes());

            assertThatExceptionOfType(InvalidBookingException.class).as(forged).isThrownBy(() -> BookingCursor.decode(cursor));
        }
    }
}
