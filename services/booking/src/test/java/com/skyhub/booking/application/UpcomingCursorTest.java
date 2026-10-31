package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.domain.InvalidBookingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class UpcomingCursorTest {

    private static final LocalDate NEXT_FLIGHT = LocalDate.of(2026, 12, 1);

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_carry_a_position_through_encode_and_decode() {
        UpcomingCursor cursor = new UpcomingCursor(NEXT_FLIGHT, "ABC234");

        assertThat(UpcomingCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");   // safe to put in an address
    }

    @Test
    void should_treat_no_cursor_as_the_first_page() {
        assertThat(UpcomingCursor.decodeOrNull(null)).isNull();
        assertThat(UpcomingCursor.decodeOrNull("")).isNull();
        assertThat(UpcomingCursor.decodeOrNull("  ")).isNull();
    }

    @Test
    void should_not_be_mixed_up_with_the_cursor_of_the_newest_first_list_in_either_direction() {
        String newestFirst = new BookingCursor(Instant.parse("2026-10-31T10:15:30Z"), "ABC234").encode();
        String soonestFirst = new UpcomingCursor(NEXT_FLIGHT, "ABC234").encode();

        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> UpcomingCursor.decode(newestFirst))
                .withMessageContaining("page");
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> BookingCursor.decode(soonestFirst))
                .withMessageContaining("page");
    }

    @Test
    void should_refuse_a_cursor_whose_parts_are_not_a_date_and_a_booking_reference() {
        for (String forged : new String[] {"u|yesterday|ABC234", "u|2026-12-01|abc", "u|2026-12-01|ABC234|extra", "u|2026-12-01|'; DROP TABLE pnr;--",
                "x|2026-12-01|ABC234", "u||", "u|2026-13-45|ABC234", "|"}) {
            assertThatExceptionOfType(InvalidBookingException.class).as(forged).isThrownBy(() -> UpcomingCursor.decode(encoded(forged)));
        }
    }

    @Test
    void should_refuse_garbage_that_is_not_encoded_or_is_longer_than_any_it_would_make() {
        for (String garbage : new String[] {"not base64 !!", "x".repeat(300), "bm90LWEtY3Vyc29y"}) {
            assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> UpcomingCursor.decode(garbage));
        }
    }
}
