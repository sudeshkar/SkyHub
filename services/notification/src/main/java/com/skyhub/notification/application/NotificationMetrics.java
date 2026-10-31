package com.skyhub.notification.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code skyhub_notifications_total{type,outcome}}: {@code sent}, {@code duplicate} (the event had already been
 * handled and was skipped) or {@code failed} (sending failed; the message will be retried, then parked).
 */
@Component
public class NotificationMetrics {

    public enum Outcome { SENT, DUPLICATE, FAILED }

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A message gave up after its retries and was copied to the dead-letter topic: a person has to look. */
    public void deadLettered() {
        registry.counter("skyhub.notifications.dead_lettered").increment();
    }

    /** Parked messages a person put back (ADR-0032). */
    public void replayed(int count) {
        registry.counter("skyhub.notifications.replayed").increment(count);
    }

    /** Parked messages a replay passed over because a person had decided they can never succeed (ADR-0037). */
    public void skipped(int count) {
        registry.counter("skyhub.notifications.skipped").increment(count);
    }

    public void record(String type, Outcome outcome) {
        registry.counter("skyhub.notifications", "type", type, "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }
}
