package com.skyhub.notification.domain;

import java.util.UUID;

/**
 * What every event this service acts on has in common (ADR-0019): a unique id (what makes handling idempotent), the
 * booking it is about, and where to write. The address is personal data, so implementations never print it.
 */
public sealed interface NotificationEvent permits BookingConfirmedEvent, BookingCancelledEvent, BookingDisruptedEvent,
        BookingRebookedEvent, BookingDisruptionWithdrawnEvent, BookingCheckInOpenEvent,
        BookingRefundCompletedEvent, AccountPasswordLinkEvent, AccountEmailChangeLinkEvent {

    UUID eventId();

    String type();

    /** The booking reference, or null for an event that belongs to no booking (an account link). */
    String locator();

    String contactEmail();
}
