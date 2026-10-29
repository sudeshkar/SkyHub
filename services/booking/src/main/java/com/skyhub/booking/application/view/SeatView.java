package com.skyhub.booking.application.view;

import com.skyhub.booking.domain.Cabin;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatStatus;

/** Seat as shown on a seat map. Deliberately omits who booked it. */
public record SeatView(String seatNumber, int row, String letter, Cabin cabin, SeatStatus status) {

    public static SeatView of(SeatInventory seat) {
        return new SeatView(seat.getSeatNumber(), seat.getRowNumber(), seat.getLetter(), seat.getCabin(),
                seat.getStatus());
    }
}
