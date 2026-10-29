package com.skyhub.booking.application;

/** Move one passenger to another seat on one flight; both are positions in the booking, in booking order. */
public record SeatChangeCommand(int passengerIndex, int segmentIndex, String seatNumber) {
}
