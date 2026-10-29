package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeatInventoryReleaseTest {

    private final SeatInventory seat = SeatInventory.available("SK501", LocalDate.of(2026, 11, 2),
            new SeatSpec(11, "D", Cabin.ECONOMY));

    @Test
    void should_free_a_seat_held_by_the_given_booking() {
        seat.book("ABC234");

        seat.release("ABC234");

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getPnrLocator()).isNull();
    }

    @Test
    void should_be_bookable_again_after_release() {
        seat.book("ABC234");
        seat.release("ABC234");

        seat.book("XYZ789");

        assertThat(seat.getPnrLocator()).isEqualTo("XYZ789");
    }

    @Test
    void should_never_free_a_seat_held_by_a_different_booking() {
        seat.book("ABC234");

        assertThatIllegalStateException().isThrownBy(() -> seat.release("XYZ789")).withMessageContaining("11D");
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
    }

    @Test
    void should_flag_releasing_a_seat_nobody_holds_as_an_inconsistency() {
        assertThatIllegalStateException().isThrownBy(() -> seat.release("ABC234"));
    }
}
