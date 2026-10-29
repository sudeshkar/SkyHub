package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeatInventoryHoldTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(600);

    private final SeatInventory seat = SeatInventory.available("SK501", LocalDate.of(2026, 11, 2),
            new SeatSpec(11, "D", Cabin.ECONOMY));

    @Test
    void should_hold_an_available_seat_for_an_owner_until_a_time() {
        seat.hold("hold-1", UNTIL);

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getHoldId()).isEqualTo("hold-1");
        assertThat(seat.getHeldUntil()).isEqualTo(UNTIL);
    }

    @Test
    void should_not_hold_a_seat_that_is_held_or_booked() {
        seat.hold("hold-1", UNTIL);
        assertThatExceptionOfType(SeatUnavailableException.class).isThrownBy(() -> seat.hold("hold-2", UNTIL))
                .withMessageContaining("11D");
        assertThat(seat.getHoldId()).isEqualTo("hold-1");

        var booked = SeatInventory.available("SK501", LocalDate.of(2026, 11, 2), new SeatSpec(12, "A", Cabin.ECONOMY));
        booked.book("ABC234");
        assertThatExceptionOfType(SeatUnavailableException.class).isThrownBy(() -> booked.hold("hold-1", UNTIL));
    }

    @Test
    void should_not_let_a_normal_booking_take_a_held_seat() {
        seat.hold("hold-1", UNTIL);

        assertThatExceptionOfType(SeatUnavailableException.class).isThrownBy(() -> seat.book("ABC234"));
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void should_release_a_hold_for_its_owner_only() {
        seat.hold("hold-1", UNTIL);

        seat.releaseHold("someone-else");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);

        seat.releaseHold("hold-1");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getHoldId()).isNull();
        assertThat(seat.getHeldUntil()).isNull();
    }

    @Test
    void should_ignore_releasing_a_hold_twice_or_on_a_booked_seat() {
        seat.hold("hold-1", UNTIL);
        seat.releaseHold("hold-1");
        seat.releaseHold("hold-1"); // safe to repeat

        seat.book("ABC234");
        seat.releaseHold("hold-1");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void should_turn_a_valid_hold_into_a_booking_and_clear_the_hold() {
        seat.hold("hold-1", UNTIL);

        seat.bookFromHold("hold-1", "ABC234", NOW.plusSeconds(120));

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(seat.getPnrLocator()).isEqualTo("ABC234");
        assertThat(seat.getHoldId()).isNull();
        assertThat(seat.getHeldUntil()).isNull();
    }

    @Test
    void should_refuse_to_book_from_an_expired_hold() {
        seat.hold("hold-1", UNTIL);

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> seat.bookFromHold("hold-1", "ABC234", UNTIL)) // exactly at expiry is expired
                .withMessageContaining("expired");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void should_refuse_to_book_from_someone_elses_hold_or_from_no_hold() {
        seat.hold("hold-1", UNTIL);
        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> seat.bookFromHold("hold-2", "ABC234", NOW));

        var free = SeatInventory.available("SK501", LocalDate.of(2026, 11, 2), new SeatSpec(12, "A", Cabin.ECONOMY));
        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> free.bookFromHold("hold-1", "ABC234", NOW));
    }
}
