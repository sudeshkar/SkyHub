package com.skyhub.booking.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Keeps who has boarded in memory, the way the table would: one row per passenger per flight, the first one wins (ADR-0060). */
final class FakeBoardedPassengers implements BoardedPassengers {

    record Row(String locator, int segmentIndex, int passengerIndex, String by, Instant at) {
    }

    final List<Row> rows = new ArrayList<>();

    @Override
    public boolean board(String locator, int segmentIndex, int passengerIndex, String by, Instant at) {
        if (isBoarded(locator, segmentIndex, passengerIndex)) {
            return false;
        }
        rows.add(new Row(locator, segmentIndex, passengerIndex, by, at));
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
}
