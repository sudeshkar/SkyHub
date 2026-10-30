package com.skyhub.flightsearch.domain;

import java.math.BigDecimal;
import java.time.Duration;
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

    /** When it is now expected to leave: the revised time if it is delayed, otherwise the scheduled one. */
    public OffsetDateTime expectedDeparture() {
        return status.kind() == FlightStatus.Kind.DELAYED && status.revisedDeparture() != null ? status.revisedDeparture() : departure;
    }

    /** A delay moves the arrival by as much as it moves the departure. The scheduled times are never changed (ADR-0025). */
    public OffsetDateTime expectedArrival() {
        return arrival.plus(Duration.between(departure, expectedDeparture()));
    }

    public FlightLeg withStatus(FlightStatus newStatus) {
        return new FlightLeg(flightNumber, origin, destination, departure, arrival, aircraftType, fare, currency, newStatus);
    }
}
