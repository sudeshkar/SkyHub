package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.domain.InvalidBookingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PastCursorTest {

    private static final LocalDate LAST_FLOWN = LocalDate.of(2026, 9, 1);

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void should_carry_a_position_through_encode_and_decode() {
        PastCursor cursor = new PastCursor(LAST_FLOWN, "ABC234");

        assertThat(PastCursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThat(cursor.encode()).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void should_treat_no_cursor_as_the_first_page() {
        assertThat(PastCursor.decodeOrNull(null)).isNull();
        assertThat(PastCursor.decodeOrNull(" ")).isNull();
    }

    @Test
    void should_not_be_mixed_up_with_either_other_kind_of_cursor_in_any_direction() {
        String past = new PastCursor(LAST_FLOWN, "ABC234").encode();
        String upcoming = new UpcomingCursor(LAST_FLOWN, "ABC234").encode();
        String newest = new BookingCursor(Instant.parse("2026-10-31T10:15:30Z"), "ABC234").encode();

        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> PastCursor.decode(upcoming));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> PastCursor.decode(newest));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> UpcomingCursor.decode(past));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> BookingCursor.decode(past));
    }

    @Test
    void should_refuse_a_cursor_whose_parts_are_not_a_date_and_a_booking_reference() {
        for (String forged : new String[] {"p|yesterday|ABC234", "p|2026-09-01|abc", "p|2026-09-01|ABC234|extra",
                "p|2026-09-01|'; DROP TABLE pnr;--", "q|2026-09-01|ABC234", "p||", "p|2026-13-45|ABC234", "|"}) {
            assertThatExceptionOfType(InvalidBookingException.class).as(forged).isThrownBy(() -> PastCursor.decode(encoded(forged)));
        }
    }

    @Test
    void should_refuse_garbage_that_is_not_encoded_or_is_longer_than_any_it_would_make() {
        for (String garbage : new String[] {"not base64 !!", "x".repeat(300), "bm90LWEtY3Vyc29y"}) {
            assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> PastCursor.decode(garbage));
        }
    }
}
