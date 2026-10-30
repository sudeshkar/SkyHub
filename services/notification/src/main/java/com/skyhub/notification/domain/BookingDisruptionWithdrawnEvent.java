package com.skyhub.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "The delay or cancellation of a flight on this booking was withdrawn" (ADR-0028, contract:
 * api-specs/events/booking-disruption-withdrawn.v1.example.json). One event per booking still active. {@code disruption}
 * is what was withdrawn: DELAYED or CANCELLED.
 */
public record BookingDisruptionWithdrawnEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                              String contactEmail, String flightNumber, LocalDate departureDate,
                                              String disruption) implements NotificationEvent {

    public static final String TYPE = "booking.disruption-withdrawn";

    @Override
    public String toString() {
        return "BookingDisruptionWithdrawnEvent[eventId=" + eventId + ", locator=" + locator + ", flight=" + flightNumber + "]";
    }
}
