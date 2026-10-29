package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeatLayoutTest {

    private final SeatLayout layout = SeatLayout.standard();

    @Test
    void should_have_12_business_and_156_economy_seats() {
        assertThat(layout.seats()).filteredOn(s -> s.cabin() == Cabin.BUSINESS).hasSize(12);
        assertThat(layout.seats()).filteredOn(s -> s.cabin() == Cabin.ECONOMY).hasSize(156);
    }

    @Test
    void should_order_seats_by_row_then_letter() {
        assertThat(layout.seats()).first().extracting(SeatSpec::seatNumber).isEqualTo("1A");
        assertThat(layout.seats()).last().extracting(SeatSpec::seatNumber).isEqualTo("35F");
    }

    @Test
    void should_skip_middle_letters_in_business() {
        assertThat(layout.seats()).extracting(SeatSpec::seatNumber).contains("1C", "1D").doesNotContain("1B", "1E");
    }
}
