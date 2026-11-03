package com.skyhub.booking.application.rebooking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code skyhub_rebookings_total{outcome}}: {@code rebooked}, {@code unchanged} (already there) or {@code refused}. */
@Component
public class RebookingMetrics {

    private final Map<String, Counter> counters;
    private final MeterRegistry registry;

    public RebookingMetrics(MeterRegistry registry) {
        this.registry = registry;
        counters = Map.of("rebooked", counter(registry, "rebooked"), "unchanged", counter(registry, "unchanged"),
                "refused", counter(registry, "refused"));
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("skyhub.rebookings").description("Staff rebookings of disrupted flights, by outcome")
                .tag("outcome", outcome).register(registry);
    }

    /** Passengers a move checked out of the flight it left (ADR-0069). Only counted when there were some. */
    public void checkInsReleased(long passengers) {
        registry.counter("skyhub.rebooking.check_ins_released").increment(passengers);
    }

    public void record(String outcome) {
        counters.get(outcome).increment();
    }
}
