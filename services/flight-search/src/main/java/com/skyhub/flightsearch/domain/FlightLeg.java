package com.skyhub.flightsearch.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One flight on one date. Times are UTC. */
public record FlightLeg(
        String flightNumber,
        String origin,
        String destination,
        OffsetDateTime departure,
        OffsetDateTime arrival,
        String aircraftType,
        BigDecimal fare,
        String currency,
        FlightStatus status) {

    /** A flight with nothing reported about it: scheduled. */
    public FlightLeg(String flightNumber, String origin, String destination, OffsetDateTime departure, OffsetDateTime arrival,
                     String aircraftType, BigDecimal fare, String currency) {
        this(flightNumber, origin, destination, departure, arrival, aircraftType, fare, currency, FlightStatus.SCHEDULED);
    }

    public FlightLeg withStatus(FlightStatus newStatus) {
        return new FlightLeg(flightNumber, origin, destination, departure, arrival, aircraftType, fare, currency, newStatus);
    }
}
