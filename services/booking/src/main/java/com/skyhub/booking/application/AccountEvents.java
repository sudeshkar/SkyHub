package com.skyhub.booking.application;

import com.skyhub.booking.domain.ContactEmail;
import java.time.Instant;

/**
 * Port: things that happen to accounts that other services care about (ADR-0033). Called inside the transaction that made the
 * change, like {@link BookingEvents}. The event carries the one-time token, so the notification service can put it in the link.
 */
public interface AccountEvents {

    void passwordLinkRequested(long accountId, ContactEmail email, String token, PasswordLinkPurpose purpose, Instant expiresAt);

    /** A link to the NEW address that, used while signed in to the account, moves the account to it (ADR-0039). */
    void emailChangeRequested(long accountId, ContactEmail newEmail, String token, Instant expiresAt);

    /**
     * The account has moved: a notice for the OLD address (ADR-0040). It names the new address only masked. It carries a one-time token
     * (ADR-0054) with which the owner of the old address takes the account back; that token can only undo this move, never make one.
     */
    void emailChanged(long accountId, ContactEmail oldEmail, ContactEmail newEmail, String undoToken, Instant undoExpiresAt);
}
