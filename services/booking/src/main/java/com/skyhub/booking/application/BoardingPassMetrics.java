package com.skyhub.booking.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code skyhub_boarding_pass_verifications_total{outcome}}: one count per pass a gate asked about (ADR-0056). */
@Component
public class BoardingPassMetrics {

    private final MeterRegistry registry;

    public BoardingPassMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome {@code valid}, or the lower-case reason a pass was refused */
    public void verified(String outcome) {
        registry.counter("skyhub.boarding_pass.verifications", "outcome", outcome).increment();
    }
}
