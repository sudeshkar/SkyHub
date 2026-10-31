package com.skyhub.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * "Send this link to the NEW address of an account" (ADR-0039, contract: api-specs/events/account-email-change-link.v1.example.json).
 * {@code contactEmail} is the new address, the one that has to prove it can be read. It belongs to no booking, so it has no
 * reference. The token is a secret: it never appears in {@link #toString()}, and neither does the address.
 */
public record AccountEmailChangeLinkEvent(UUID eventId, String type, int version, Instant occurredAt, String contactEmail,
                                          String token, Instant expiresAt) implements NotificationEvent {

    public static final String TYPE = "account.email-change-link";

    @Override
    public String locator() {
        return null;
    }

    @Override
    public String toString() {
        return "AccountEmailChangeLinkEvent[eventId=" + eventId + "]";
    }
}
