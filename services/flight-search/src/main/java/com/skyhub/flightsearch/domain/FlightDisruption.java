package com.skyhub.flightsearch.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * "This flight was delayed or cancelled" (ADR-0025), as the booking service publishes it on the compacted topic. The shape is
 * the contract in api-specs/events/flight-disrupted.v1.example.json. Fields added later are ignored.
 */
public record FlightDisruption(UUID eventId, String type, int version, Instant occurredAt, String flightNumber,
                               LocalDate departureDate, String disruption, OffsetDateTime revisedDeparture, String reason) {

    public static final String TYPE = "flight.disrupted";

    /** A withdrawn disruption replaces the earlier one on the compacted topic, so it must travel as an event (ADR-0026). */
    public static final String CLEARED = "CLEARED";

    public FlightStatus status() {
        return switch (disruption) {
            case "CANCELLED" -> FlightStatus.cancelled();
            case CLEARED -> FlightStatus.SCHEDULED;
            default -> FlightStatus.delayedTo(revisedDeparture);
        };
    }
}
