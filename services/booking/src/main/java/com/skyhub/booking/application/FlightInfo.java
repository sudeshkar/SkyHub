package com.skyhub.booking.application;

import com.skyhub.booking.domain.Money;
import java.time.Instant;

/** What the booking service needs to know about one flight on one date. */
public record FlightInfo(Instant departure, Money fare, String origin, String destination) {

    /** A flight whose route is not known. Rebooking refuses to move anyone to or from such a flight. */
    public FlightInfo(Instant departure, Money fare) {
        this(departure, fare, null, null);
    }
}
