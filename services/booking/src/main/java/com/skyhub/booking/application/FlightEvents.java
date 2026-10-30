package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;

/**
 * Port: the state of flights, for services that follow it (ADR-0025). Like {@link BookingEvents}, calls are made inside the
 * transaction that made the change. Events carry no personal data.
 */
public interface FlightEvents {

    /** A flight was delayed or cancelled, or its record changed. Not called when a repeated request changes nothing. */
    void flightDisrupted(Disruption disruption);

    /** A disruption was withdrawn: the flight is back to its schedule. Replaces the disruption on the compacted topic. */
    void flightCleared(String flightNumber, java.time.LocalDate departureDate);
}
