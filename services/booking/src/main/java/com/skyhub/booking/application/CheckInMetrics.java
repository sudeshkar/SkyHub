package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code skyhub_check_ins_total{outcome}}: one count per check-in request. */
@Component
public class CheckInMetrics {

    public enum Outcome {
        CHECKED_IN, REPLAYED, REFUSED;

        String tag() {
            return name().toLowerCase();
        }
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public CheckInMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("skyhub.check_ins")
                    .description("Check-in requests by outcome")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
