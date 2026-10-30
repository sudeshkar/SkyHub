package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code skyhub_checkin_reminders_total{outcome}}: reminders queued, flights skipped because their time is unknown, and failed sweeps. */
@Component
public class CheckInReminderMetrics {

    public enum Outcome { SENT, FLIGHT_UNAVAILABLE, FAILED }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public CheckInReminderMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("skyhub.checkin.reminders")
                    .description("Check-in-open reminders, by outcome")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
    }

    public void record(Outcome outcome, int count) {
        counters.get(outcome).increment(count);
    }
}
