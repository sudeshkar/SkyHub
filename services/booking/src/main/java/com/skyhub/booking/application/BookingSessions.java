package com.skyhub.booking.application;

/**
 * Port: which sessions of a booking are still current (ADR-0057). A booking token carries the epoch it was issued under, and signing out
 * of the booking raises the booking's epoch, which ends every session issued before, a copied token included. The persistence layer
 * implements it, so the security layer that checks tokens does not know how this is stored. Kept apart from the booking itself, so
 * signing out never touches the booking's own row or version.
 */
public interface BookingSessions {

    /** The epoch a token issued now should carry. A booking nobody signed out of is at 0. */
    int currentEpoch(String locator);

    /** True only if {@code epoch} is the booking's current one. One lookup by key, never cached, so an ended session is refused at once. */
    boolean accepts(String locator, int epoch);

    /** Ends every session of the booking issued so far. */
    void endAll(String locator);
}
