package com.skyhub.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Staff moved a flight of this booking onto another" (ADR-0023, contract:
 * api-specs/events/booking-rebooked.v1.example.json). Carries every passenger's new seat, so the email can list them.
 *
 * <p>{@code checkedOut} says the move also checked the party out of the flight they left (ADR-0069), so the email can tell them their old
 * boarding passes no longer work and to check in again (ADR-0070). An event sent before the field existed has none and reads as false.
 */
public record BookingRebookedEvent(UUID eventId, String type, int version, Instant occurredAt, String locator,
                                   String contactEmail, BookingConfirmedEvent.Segment from, BookingConfirmedEvent.Segment to,
                                   List<SeatedPassenger> passengers, Boolean checkedOut) implements NotificationEvent {

    public static final String TYPE = "booking.rebooked";

    /** A boxed {@code Boolean} on purpose: the parser refuses a missing primitive, and an older event has no such field, so absent means false. */
    public BookingRebookedEvent {
        checkedOut = checkedOut != null && checkedOut;
    }

    public record SeatedPassenger(String firstName, String lastName, String seat) {
    }

    @Override
    public String toString() {
        return "BookingRebookedEvent[eventId=" + eventId + ", locator=" + locator + "]";
    }
}
