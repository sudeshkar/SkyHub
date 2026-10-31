package com.skyhub.booking.application;

/**
 * Port: is a signed-in session still current? An account token carries the epoch it was issued under, and the account's epoch goes up
 * when its credentials change (ADR-0041), which ends every session issued before. The persistence layer implements it, so the
 * security layer that checks tokens does not know how accounts are stored.
 */
public interface AccountSessions {

    /** True only if the account exists and its epoch is {@code epoch}. One lookup by key, never cached, so a revoked session is refused at once. */
    boolean accepts(long accountId, int epoch);
}
