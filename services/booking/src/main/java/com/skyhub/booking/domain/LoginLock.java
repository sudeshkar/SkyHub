package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;

/**
 * The one rule for locking an account after wrong passwords (ADR-0045): five wrong passwords lock it for fifteen minutes, a success
 * forgets the count, and once a lock has run out the count starts afresh. Staff accounts (ADR-0017) and traveller accounts (ADR-0033)
 * each hold one, in the columns they already had, so the rule is written, and tested, once.
 */
@Embeddable
public class LoginLock {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void recordFailure(Instant now) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) { // an old lock has run out: start counting afresh
            lockedUntil = null;
            failedAttempts = 0;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
            failedAttempts = 0;
        }
    }

    /** A correct password: the count and any lock are forgotten. */
    public void recordSuccess() {
        clear();
    }

    /** Forgets the count and any lock, for instance when a new password is chosen. */
    public void clear() {
        failedAttempts = 0;
        lockedUntil = null;
    }
}
