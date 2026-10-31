package com.skyhub.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * "Send this person a link to choose a password" (ADR-0033, contract: api-specs/events/account-password-link.v1.example.json).
 * {@code purpose} is SIGN_UP or RESET. It belongs to no booking, so it has no reference. The token arrives sealed
 * (ADR-0046) and is opened only to build the link. It never appears in {@link #toString()}, and neither does the address.
 */
public record AccountPasswordLinkEvent(UUID eventId, String type, int version, Instant occurredAt, String contactEmail,
                                       String purpose, String sealedToken, Instant expiresAt) implements NotificationEvent {

    public static final String TYPE = "account.password-link";

    @Override
    public String locator() {
        return null;
    }

    @Override
    public String toString() {
        return "AccountPasswordLinkEvent[eventId=" + eventId + ", purpose=" + purpose + "]";
    }
}
