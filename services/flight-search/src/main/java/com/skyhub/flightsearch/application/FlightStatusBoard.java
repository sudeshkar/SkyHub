package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.FlightDisruption;
import com.skyhub.flightsearch.domain.FlightStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The current state of every disrupted flight, held in memory (ADR-0025). A hash map keyed by flight and date: a lookup is
 * O(1), and nothing touches a database or the network during a search. Rebuilt on every start by replaying the compacted
 * topic, so it needs no storage of its own.
 *
 * <p>Safe for many threads: the Kafka thread writes while request threads read, and a report only replaces what it
 * is newer than (so a replay or a late message cannot undo a newer one). Memory is bounded: flights that left more than a
 * day ago are not kept.
 */
@Component
public class FlightStatusBoard implements FlightStatusSource {

    public enum Outcome { APPLIED, STALE, EXPIRED }

    private record Key(String flightNumber, LocalDate date) {
    }

    private record Entry(FlightStatus status, Instant reportedAt) {
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Map<Outcome, Counter> counters = new java.util.EnumMap<>(Outcome.class);

    public FlightStatusBoard(Clock clock, MeterRegistry registry) {
        this.clock = clock;
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("skyhub.flight.status.events")
                    .description("Flight disruption events read from Kafka, by outcome")
                    .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).register(registry));
        }
        Gauge.builder("skyhub.flight.status.board.size", entries, Map::size)
                .description("Disrupted flights currently held in memory").register(registry);
    }

    @Override
    public FlightStatus statusOf(String flightNumber, LocalDate date) {
        Entry entry = entries.get(new Key(flightNumber, date));
        return entry == null ? FlightStatus.SCHEDULED : entry.status();
    }

    public Outcome apply(FlightDisruption event) {
        Outcome outcome = place(event);
        counters.get(outcome).increment();
        return outcome;
    }

    private Outcome place(FlightDisruption event) {
        if (event.departureDate().isBefore(today().minusDays(1))) {
            return Outcome.EXPIRED;
        }
        Entry incoming = new Entry(event.status(), event.occurredAt());
        boolean[] replaced = {false};
        entries.merge(new Key(event.flightNumber(), event.departureDate()), incoming, (current, next) -> {
            if (next.reportedAt().isBefore(current.reportedAt())) {
                return current;   // an older report arriving late (a replay): keep what is newer
            }
            replaced[0] = true;
            return next;
        });
        return replaced[0] || entries.get(new Key(event.flightNumber(), event.departureDate())) == incoming
                ? Outcome.APPLIED : Outcome.STALE;
    }

    /** Forgets flights that left more than a day ago. @return how many were removed */
    public int purge() {
        return purgeAsOf(today());
    }

    int purgeAsOf(LocalDate today) {
        int before = entries.size();
        entries.keySet().removeIf(key -> key.date().isBefore(today.minusDays(1)));
        return before - entries.size();
    }

    public int size() {
        return entries.size();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
