package com.skyhub.booking.application;

import java.time.LocalDate;

/** Port: hands out boarding sequence numbers for a flight, never the same number twice. */
public interface BoardingSequence {

    /**
     * Reserves {@code count} consecutive numbers.
     *
     * @return the first of them (numbers start at 1)
     */
    int reserve(String flightNumber, LocalDate departureDate, int count);
}
