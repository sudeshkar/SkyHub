package com.skyhub.booking.application;

import java.time.Instant;
import java.util.Collection;

/**
 * Port: which passengers have boarded which flight (ADR-0060). One record per passenger per flight; the first scan is the one that counts
 * and who made it is kept. The persistence layer implements it, so neither the gate check nor check-out knows how it is stored.
 */
public interface BoardedPassengers {

    /** Records the passenger as boarded, once. @return false if they had already boarded, in which case nothing changes */
    boolean board(String locator, int segmentIndex, int passengerIndex, String by, Instant at);

    boolean isBoarded(String locator, int segmentIndex, int passengerIndex);

    /** Whether any of these passengers has boarded this flight: someone on the aircraft cannot check out. */
    boolean anyBoarded(String locator, int segmentIndex, Collection<Integer> passengerIndexes);
}
