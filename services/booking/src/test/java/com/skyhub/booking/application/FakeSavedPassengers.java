package com.skyhub.booking.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Keeps saved passengers in memory, the way the table would: oldest first, a limit for each account, and nobody sees another's (ADR-0078). */
final class FakeSavedPassengers implements SavedPassengers {

    private record Row(long account, Saved saved) {
    }

    private final List<Row> rows = new ArrayList<>();
    private long next = 1;

    @Override
    public List<Saved> of(long accountId) {
        return rows.stream().filter(r -> r.account() == accountId).map(Row::saved).toList();
    }

    @Override
    public Optional<Saved> add(long accountId, String firstName, String lastName, int limit) {
        if (of(accountId).size() >= limit) {
            return Optional.empty();
        }
        Saved saved = new Saved(next++, firstName, lastName);
        rows.add(new Row(accountId, saved));
        return Optional.of(saved);
    }

    @Override
    public boolean remove(long accountId, long id) {
        return rows.removeIf(r -> r.account() == accountId && r.saved().id() == id);
    }
}
