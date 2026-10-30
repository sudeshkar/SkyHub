package com.skyhub.booking.application.rebooking;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code skyhub_rebookings_total{outcome}}: {@code rebooked}, {@code unchanged} (already there) or {@code refused}. */
@Component
public class RebookingMetrics {

    private final Map<String, Counter> counters;

    public RebookingMetrics(MeterRegistry registry) {
        counters = Map.of("rebooked", counter(registry, "rebooked"), "unchanged", counter(registry, "unchanged"),
                "refused", counter(registry, "refused"));
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("skyhub.rebookings").description("Staff rebookings of disrupted flights, by outcome")
                .tag("outcome", outcome).register(registry);
    }

    public void record(String outcome) {
        counters.get(outcome).increment();
    }
}
