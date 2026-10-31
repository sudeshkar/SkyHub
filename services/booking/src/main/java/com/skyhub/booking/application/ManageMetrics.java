package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code skyhub_manage_actions_total{action}}. A rising {@code session_denied} rate is the signal for
 * someone guessing booking references and last names. {@code sessions_ended} is a sign-out of a booking (ADR-0057) and
 * {@code session_revoked} a token refused because of one.
 */
@Component
public class ManageMetrics {

    public enum Action {
        SESSION_OPENED, SESSION_DENIED, SESSIONS_ENDED, SESSION_REVOKED, CANCELLED, SEAT_CHANGED, SEAT_TAKEN;

        String tag() {
            return name().toLowerCase();
        }
    }

    private final Map<Action, Counter> counters = new EnumMap<>(Action.class);

    public ManageMetrics(MeterRegistry registry) {
        for (Action action : Action.values()) {
            counters.put(action, Counter.builder("skyhub.manage.actions")
                    .description("Manage-booking actions by type")
                    .tag("action", action.tag())
                    .register(registry));
        }
    }

    public void record(Action action) {
        counters.get(action).increment();
    }
}
