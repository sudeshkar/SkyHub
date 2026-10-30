package com.skyhub.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Staff moved a flight of this booking onto another" (ADR-0023, contract:
 * api-specs/events/booking-rebooked.v1.example.json). Carries every passenger's new seat, so the email can list them.
 */
public record BookingRebookedEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                   String contactEmail, BookingConfirmedEvent.Segment from, BookingConfirmedEvent.Segment to,
                                   List<SeatedPassenger> passengers) implements NotificationEvent {

    public static final String TYPE = "booking.rebooked";

    public record SeatedPassenger(String firstName, String lastName, String seat) {
    }

    @Override
    public String toString() {
        return "BookingRebookedEvent[eventId=" + eventId + ", locator=" + locator + "]";
    }
}
