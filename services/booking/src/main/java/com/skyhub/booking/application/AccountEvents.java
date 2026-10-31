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
}
