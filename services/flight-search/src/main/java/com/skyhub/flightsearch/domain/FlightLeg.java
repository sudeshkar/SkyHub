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
        String currency) {
}
