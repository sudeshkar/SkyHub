package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Business metric: every booking attempt ends in exactly one outcome. Exposed to Prometheus as
 * {@code skyhub_bookings_total{outcome}}. Labels are a small fixed set: never locators or flights.
 */
@Component
public class BookingMetrics {

    public enum Outcome {
        CREATED, REPLAYED, SEAT_TAKEN, REJECTED;

        String tag() {
            return name().toLowerCase();
        }
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public BookingMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("skyhub.bookings")
                    .description("Booking attempts by outcome")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
