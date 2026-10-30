package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PnrRebookTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final PnrSegment DISRUPTED = new PnrSegment("SK501", DAY);
    private static final PnrSegment NEXT_DAY = new PnrSegment("SK501", DAY.plusDays(1));

    private Pnr pnr;

    @BeforeEach
    void setUp() {
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK100", DAY), DISRUPTED),
                List.of(new PnrPassenger("Priya", "Perera", List.of("3A", "11D")),
                        new PnrPassenger("Anil", "Perera", List.of("3B", "11E"))),
                Instant.parse("2026-10-30T10:00:00Z"), "key", "fingerprint");
    }

    @Test
    void should_move_one_flight_and_its_seats_and_leave_the_other_flights_alone() {
        PnrSegment left = pnr.rebook(1, NEXT_DAY, List.of("14A", "14B"));

        assertThat(left).isEqualTo(DISRUPTED);
        assertThat(pnr.getSegments()).containsExactly(new PnrSegment("SK100", DAY), NEXT_DAY);
        assertThat(pnr.seatOf(0, 1)).isEqualTo("14A");
        assertThat(pnr.seatOf(1, 1)).isEqualTo("14B");
        assertThat(pnr.seatOf(0, 0)).isEqualTo("3A");
        assertThat(pnr.seatOf(1, 0)).isEqualTo("3B");
    }

    @Test
    void should_refuse_a_cancelled_booking() {
        pnr.cancel();

        assertThatExceptionOfType(BookingCancelledException.class).isThrownBy(() -> pnr.rebook(1, NEXT_DAY, List.of("14A", "14B")));
    }

    @Test
    void should_refuse_a_flight_the_booking_does_not_have() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pnr.rebook(5, NEXT_DAY, List.of("14A", "14B")))
                .withMessageContaining("no flight 5");
    }

    @Test
    void should_refuse_the_wrong_number_of_seats_or_the_same_seat_twice_and_change_nothing() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pnr.rebook(1, NEXT_DAY, List.of("14A")));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pnr.rebook(1, NEXT_DAY, List.of("14A", "14A")));

        assertThat(pnr.getSegments().get(1)).isEqualTo(DISRUPTED);
        assertThat(pnr.seatOf(0, 1)).isEqualTo("11D");
    }

    @Test
    void should_refuse_a_flight_the_booking_already_has_on_another_leg() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> pnr.rebook(1, new PnrSegment("SK100", DAY), List.of("14A", "14B")))
                .withMessageContaining("already");
    }
}
