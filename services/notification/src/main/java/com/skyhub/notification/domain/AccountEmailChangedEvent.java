package com.skyhub.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * "This account has moved to another address" (ADR-0040, contract: api-specs/events/account-email-changed.v1.example.json).
 * {@code contactEmail} is the OLD address, the one to warn. The new address appears only masked. The notice carries a one-time token,
 * sealed (ADR-0046), with which the owner of the old address takes the account back (ADR-0054); it can only undo this move, never make
 * one. A notice made before that existed has neither field. It belongs to no booking, so it has no reference. Neither the old address
 * nor the token appears in {@link #toString()}.
 */
public record AccountEmailChangedEvent(UUID eventId, String type, int version, Instant occurredAt, String contactEmail,
                                       String newEmailMasked, String sealedUndoToken, Instant undoExpiresAt) implements NotificationEvent {

    public static final String TYPE = "account.email-changed";

    public boolean canBeUndone() {
        return sealedUndoToken != null;
    }

    @Override
    public String locator() {
        return null;
    }

    @Override
    public String toString() {
        return "AccountEmailChangedEvent[eventId=" + eventId + "]";
    }
}
