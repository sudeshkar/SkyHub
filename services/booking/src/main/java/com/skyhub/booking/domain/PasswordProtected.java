package com.skyhub.booking.domain;

import java.time.Instant;

/**
 * What the password gate needs from any kind of account that has a password and a lock (ADR-0045). A new kind of account implements
 * this and uses the same gate; nothing else about how the account is stored or named matters to it.
 */
public interface PasswordProtected {

    /** The stored hash, or null when no password has been chosen yet (a traveller who has not used their emailed link). */
    String getPasswordHash();

    boolean isLockedAt(Instant now);

    void recordFailedLogin(Instant now);

    void recordSuccessfulLogin();
}
