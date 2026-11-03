package com.skyhub.booking.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code skyhub_saved_passengers_total{outcome}}: {@code added}, {@code already_saved}, {@code removed}, {@code full}, {@code not_found} (ADR-0078). */
@Component
public class SavedPassengerMetrics {

    private final MeterRegistry registry;

    public SavedPassengerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String outcome) {
        registry.counter("skyhub.saved_passengers", "outcome", outcome).increment();
    }
}
