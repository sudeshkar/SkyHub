package com.skyhub.booking.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * How one flight's boarding stands, for operations (ADR-0065): how many passengers are checked in and who has boarded. It names booking
 * references and places on the booking, never people, so it can be shown or passed on without personal data.
 *
 * @param boardings oldest first; bounded by the seats on the aircraft
 */
public record FlightBoarding(String flightNumber, LocalDate departureDate, int checkedIn, List<Boarding> boardings) {

    /** One passenger's boarding; {@code segmentIndex} and {@code passengerIndex} count from 0, as {@code BoardingReversalService} expects. */
    public record Boarding(String locator, int segmentIndex, int passengerIndex, String boardedBy, Instant boardedAt) {
    }

    public int boarded() {
        return boardings.size();
    }

    /** Checked in and not yet boarded. The two counts are read a moment apart, so this is clamped rather than ever shown below zero. */
    public int waiting() {
        return Math.max(0, checkedIn - boardings.size());
    }
}
