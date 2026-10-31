package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * {@code skyhub_my_bookings_total{outcome}} (listed, opened, denied) and {@code skyhub_contact_index_backfilled_total}
 * (bookings given an address index by the start-up backfill).
 */
@Component
public class MyBookingsMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public MyBookingsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void listed() {
        outcome("listed");
    }

    public void opened() {
        outcome("opened");
    }

    public void denied() {
        outcome("denied");
    }

    public void backfilled(int bookings) {
        counters.computeIfAbsent("backfilled", key -> Counter.builder("skyhub.contact.index.backfilled")
                .description("Old bookings given an address index by the start-up backfill").register(registry)).increment(bookings);
    }

    /** Old bookings whose address could not be read in a start-up backfill, once per run (ADR-0034). */
    public void unreadable(int bookings) {
        counters.computeIfAbsent("unreadable", key -> Counter.builder("skyhub.contact.index.unreadable")
                .description("Bookings whose address could not be read during a start-up backfill").register(registry)).increment(bookings);
    }

    private void outcome(String outcome) {
        counters.computeIfAbsent(outcome, key -> Counter.builder("skyhub.my.bookings")
                .description("A signed-in traveller's bookings: listed, opened, or denied (not theirs or unknown)")
                .tag("outcome", outcome).register(registry)).increment();
    }
}
