package com.skyhub.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Online check-in is now open for a flight on this booking" (ADR-0029, contract:
 * api-specs/events/booking-check-in-open.v1.example.json). {@code departure} is the expected time (the revised one if the
 * flight was delayed); {@code checkInCloses} is when check-in stops.
 */
public record BookingCheckInOpenEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                      String contactEmail, String flightNumber, LocalDate departureDate,
                                      Instant departure, Instant checkInCloses) implements NotificationEvent {

    public static final String TYPE = "booking.check-in-open";

    @Override
    public String toString() {
        return "BookingCheckInOpenEvent[eventId=" + eventId + ", locator=" + locator + ", flight=" + flightNumber + "]";
    }
}
