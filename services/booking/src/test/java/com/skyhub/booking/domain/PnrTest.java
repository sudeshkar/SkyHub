package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PnrTest {

    private final Pnr pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
            List.of(new PnrSegment("SK501", LocalDate.of(2026, 11, 2)), new PnrSegment("SK901", LocalDate.of(2026, 11, 2))),
            List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A")),
                    new PnrPassenger("Raj", "O'Neil", List.of("11E", "24B"))),
            Instant.parse("2026-10-29T10:00:00Z"), "key-12345678", "fingerprint-1");

    @Test
    void should_recognise_the_request_that_created_it() {
        assertThat(pnr.wasCreatedBy("fingerprint-1")).isTrue();
        assertThat(pnr.wasCreatedBy("fingerprint-2")).isFalse();
    }

    @Test
    void should_be_confirmed_with_segments_and_seats() {
        assertThat(pnr.getStatus()).isEqualTo(PnrStatus.CONFIRMED);
        assertThat(pnr.getSegments()).extracting(PnrSegment::flightNumber).containsExactly("SK501", "SK901");
        assertThat(pnr.getPassengers().getFirst().getSeats()).containsExactly("11D", "24A");
    }

    @Test
    void should_match_any_passenger_last_name_ignoring_case_and_spaces() {
        assertThat(pnr.hasPassengerWithLastName("perera")).isTrue();
        assertThat(pnr.hasPassengerWithLastName(" O'NEIL ")).isTrue();
    }

    @Test
    void should_not_match_unknown_last_name() {
        assertThat(pnr.hasPassengerWithLastName("Silva")).isFalse();
    }
}
