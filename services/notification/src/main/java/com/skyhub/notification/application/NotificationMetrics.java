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

    public void record(String type, Outcome outcome) {
        registry.counter("skyhub.notifications", "type", type, "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                .increment();
    }
}
