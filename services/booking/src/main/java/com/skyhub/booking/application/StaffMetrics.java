package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code skyhub_staff_logins_total{outcome}}: {@code success}, {@code failed} (wrong password or unknown user) and
 * {@code locked} (refused because the account is locked). A burst of the last two is someone guessing.
 */
@Component
public class StaffMetrics {

    private final Map<String, Counter> counters;

    public StaffMetrics(MeterRegistry registry) {
        counters = Map.of(
                "success", counter(registry, "success"),
                "failed", counter(registry, "failed"),
                "locked", counter(registry, "locked"));
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("skyhub.staff.logins")
                .description("Staff login attempts, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    public void login(String outcome) {
        counters.get(outcome).increment();
    }
}
