package com.skyhub.booking.application;

import com.skyhub.booking.domain.Money;
import java.time.Instant;

/**
 * What the booking service needs to know about one flight on one date. {@code arrival} is the scheduled landing time, null when
 * it is not known; the connection rule for rebooking (ADR-0036) refuses rather than guesses when it needs one that is missing.
 */
public record FlightInfo(Instant departure, Money fare, String origin, String destination, Instant arrival) {

    /** A flight whose route and arrival are not known. Rebooking refuses to move anyone to or from such a flight. */
    public FlightInfo(Instant departure, Money fare) {
        this(departure, fare, null, null, null);
    }

    /** A flight with a known route and no known arrival. */
    public FlightInfo(Instant departure, Money fare, String origin, String destination) {
        this(departure, fare, origin, destination, null);
    }
}
