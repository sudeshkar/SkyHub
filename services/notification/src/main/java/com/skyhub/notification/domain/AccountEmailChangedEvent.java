package com.skyhub.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * "This account has moved to another address" (ADR-0040, contract: api-specs/events/account-email-changed.v1.example.json).
 * {@code contactEmail} is the OLD address, the one to warn. The new address appears only masked, and there is no token or link:
 * a notice must not be a second way to change an account. It belongs to no booking, so it has no reference. The old address never
 * appears in {@link #toString()}.
 */
public record AccountEmailChangedEvent(UUID eventId, String type, int version, Instant occurredAt, String contactEmail,
                                       String newEmailMasked) implements NotificationEvent {

    public static final String TYPE = "account.email-changed";

    @Override
    public String locator() {
        return null;
    }

    @Override
    public String toString() {
        return "AccountEmailChangedEvent[eventId=" + eventId + "]";
    }
}
