package com.skyhub.booking.application;

import java.time.LocalDate;

/** One seat on one flight on one date: what a hold asks for and what it reports back. */
public record HeldSeat(String flightNumber, LocalDate departureDate, String seatNumber) {
}
