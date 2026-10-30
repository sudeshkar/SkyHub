package com.skyhub.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "A flight on this booking was delayed or cancelled" (ADR-0019, contract:
 * api-specs/events/booking-disrupted.v1.example.json). One event per affected booking. {@code disruption} is DELAYED
 * (with {@code revisedDeparture}) or CANCELLED (without).
 */
public record BookingDisruptedEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                    String contactEmail, String flightNumber, LocalDate departureDate,
                                    String disruption, Instant revisedDeparture, String reason)
        implements NotificationEvent {

    public static final String TYPE = "booking.disrupted";

    @Override
    public String toString() {
        return "BookingDisruptedEvent[eventId=" + eventId + ", locator=" + locator + ", flight=" + flightNumber + "]";
    }
}
