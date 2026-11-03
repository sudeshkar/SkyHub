package com.skyhub.booking.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps who has boarded in memory, the way the table would: one row per passenger per flight, the first one wins (ADR-0060). */
final class FakeBoardedPassengers implements BoardedPassengers {

    record Row(String locator, int segmentIndex, int passengerIndex, String by, Instant at) {
    }

    /** What the reversal table would hold (ADR-0064): the boarding that was taken back, who took it back, and why. */
    record Reversal(Row boarding, String reversedBy, String reason, Instant at) {
    }

    final List<Row> rows = new ArrayList<>();
    /** Why each late boarding was allowed (ADR-0076), by who boarded and what: only boardings made after the gate closed have one. */
    final java.util.Map<Row, String> lateReasons = new java.util.HashMap<>();
    final List<Reversal> reversals = new ArrayList<>();

    @Override
    public boolean board(String locator, int segmentIndex, int passengerIndex, String by, Instant at) {
        if (isBoarded(locator, segmentIndex, passengerIndex)) {
            return false;
        }
        rows.add(new Row(locator, segmentIndex, passengerIndex, by, at));
        return true;
    }

    @Override
    public boolean boardLate(String locator, int segmentIndex, int passengerIndex, String by, Instant at, String reason) {
        if (!board(locator, segmentIndex, passengerIndex, by, at)) {
            return false;
        }
        lateReasons.put(new Row(locator, segmentIndex, passengerIndex, by, at), reason);
        return true;
    }

    @Override
    public boolean isBoarded(String locator, int segmentIndex, int passengerIndex) {
        return rows.stream().anyMatch(r -> r.locator().equals(locator) && r.segmentIndex() == segmentIndex && r.passengerIndex() == passengerIndex);
    }

    @Override
    public boolean anyBoarded(String locator, int segmentIndex, Collection<Integer> passengerIndexes) {
        return passengerIndexes.stream().anyMatch(p -> isBoarded(locator, segmentIndex, p));
    }

    @Override
    public boolean anyBoardedOnFlight(String locator, int segmentIndex) {
        return rows.stream().anyMatch(r -> r.locator().equals(locator) && r.segmentIndex() == segmentIndex);
    }

    @Override
    public boolean undo(String locator, int segmentIndex, int passengerIndex, String by, String reason, Instant at) {
        Row boarding = rows.stream().filter(r -> r.locator().equals(locator) && r.segmentIndex() == segmentIndex && r.passengerIndex() == passengerIndex)
                .findFirst().orElse(null);
        if (boarding == null) {
            return false;
        }
        rows.remove(boarding);
        reversals.add(new Reversal(boarding, by, reason, at));
        return true;
    }
}
