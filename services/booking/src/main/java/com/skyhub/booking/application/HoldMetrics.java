package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code skyhub_seat_holds_total{outcome}}: how holds end. A high expired-to-converted ratio means people abandon checkout. */
@Component
public class HoldMetrics {

    public enum Outcome {
        CREATED, RELEASED, EXPIRED, CONFLICT, CONVERTED;

        String tag() {
            return name().toLowerCase();
        }
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public HoldMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("skyhub.seat.holds")
                    .description("Seat holds by how they ended")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }

    public void record(Outcome outcome, int amount) {
        counters.get(outcome).increment(amount);
    }
}
