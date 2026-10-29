package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PnrManagementTest {

    private final Pnr pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
            List.of(new PnrSegment("SK501", LocalDate.of(2026, 11, 2)), new PnrSegment("SK901", LocalDate.of(2026, 11, 2))),
            List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A")),
                    new PnrPassenger("Raj", "Perera", List.of("11E", "24B"))),
            Instant.parse("2026-10-29T10:00:00Z"), "key-hash", "fingerprint");

    @Test
    void should_cancel_once_and_report_whether_anything_changed() {
        assertThat(pnr.cancel()).isTrue();
        assertThat(pnr.getStatus()).isEqualTo(PnrStatus.CANCELLED);

        assertThat(pnr.cancel()).isFalse();
        assertThat(pnr.getStatus()).isEqualTo(PnrStatus.CANCELLED);
    }

    @Test
    void should_return_the_seat_a_passenger_holds_on_a_flight() {
        assertThat(pnr.seatOf(0, 1)).isEqualTo("24A");
        assertThat(pnr.seatOf(1, 0)).isEqualTo("11E");
    }

    @Test
    void should_move_a_passenger_and_return_the_seat_they_left() {
        String left = pnr.changeSeat(0, 1, "30C");

        assertThat(left).isEqualTo("24A");
        assertThat(pnr.getPassengers().getFirst().getSeats()).containsExactly("11D", "30C");
        assertThat(pnr.getPassengers().get(1).getSeats()).containsExactly("11E", "24B");
    }

    @Test
    void should_refuse_a_seat_already_held_by_another_passenger_on_the_same_flight() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> pnr.changeSeat(0, 0, "11E"))
                .withMessageContaining("11E");
    }

    @Test
    void should_allow_a_seat_that_another_passenger_holds_on_a_different_flight() {
        pnr.changeSeat(0, 0, "24B"); // 24B is Raj's seat on SK901, not on SK501

        assertThat(pnr.seatOf(0, 0)).isEqualTo("24B");
    }

    @Test
    void should_refuse_changes_to_a_cancelled_booking() {
        pnr.cancel();

        assertThatExceptionOfType(BookingCancelledException.class).isThrownBy(() -> pnr.changeSeat(0, 0, "30C"));
    }

    @Test
    void should_reject_passenger_or_flight_positions_that_do_not_exist() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pnr.changeSeat(2, 0, "30C"));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pnr.changeSeat(0, 2, "30C"));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pnr.seatOf(-1, 0));
    }
}
