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

    /**
     * Records the passenger as boarded after the gate closed, once, with why (ADR-0076). The same one row as {@link #board}, so a passenger
     * boards once however they got on. @return false if they had already boarded, in which case nothing changes
     */
    boolean boardLate(String locator, int segmentIndex, int passengerIndex, String by, Instant at, String reason);

    boolean isBoarded(String locator, int segmentIndex, int passengerIndex);

    /** Whether anyone on the booking has boarded this flight: a booking with a passenger on the aircraft is not moved (ADR-0069). */
    boolean anyBoardedOnFlight(String locator, int segmentIndex);

    /** Whether any of these passengers has boarded this flight: someone on the aircraft cannot check out. */
    boolean anyBoarded(String locator, int segmentIndex, Collection<Integer> passengerIndexes);

    /**
     * Takes a boarding back and keeps a record of the correction (ADR-0064): who scanned and when, who took it back, when and why. The
     * boarding and its record change together or not at all, and when two requests race exactly one of them wins.
     *
     * @return false if that passenger had not boarded (or was already taken back), in which case nothing changes
     */
    boolean undo(String locator, int segmentIndex, int passengerIndex, String by, String reason, Instant at);
}
