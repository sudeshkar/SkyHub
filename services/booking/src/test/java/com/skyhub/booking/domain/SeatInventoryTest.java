package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeatInventoryTest {

    private final SeatInventory seat = SeatInventory.available("SK501", LocalDate.of(2026, 11, 2),
            new SeatSpec(11, "D", Cabin.ECONOMY));

    @Test
    void should_start_available() {
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.getSeatNumber()).isEqualTo("11D");
    }

    @Test
    void should_book_available_seat_for_locator() {
        seat.book("ABC234");

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(seat.getPnrLocator()).isEqualTo("ABC234");
    }

    @Test
    void should_refuse_to_book_a_booked_seat() {
        seat.book("ABC234");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> seat.book("XYZ789"))
                .withMessageContaining("11D");
        assertThat(seat.getPnrLocator()).isEqualTo("ABC234");
    }
}
