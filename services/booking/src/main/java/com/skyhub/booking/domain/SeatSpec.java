package com.skyhub.booking.domain;

/** A seat position in an aircraft layout, independent of any flight. */
public record SeatSpec(int row, String letter, Cabin cabin) {

    public String seatNumber() {
        return row + letter;
    }
}
