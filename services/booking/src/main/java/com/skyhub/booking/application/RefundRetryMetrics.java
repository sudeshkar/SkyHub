package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** {@code skyhub_refund_retries_total{source,outcome}}. {@code gave_up} means a person has to refund by hand. */
@Component
public class RefundRetryMetrics {

    public enum Outcome { REFUNDED, FAILED, GAVE_UP }

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public RefundRetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String source, Outcome outcome) {
        counters.computeIfAbsent(source + "/" + outcome, key -> Counter.builder("skyhub.refund.retries")
                .description("Refund retries, by source and outcome")
                .tag("source", source).tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry)).increment();
    }
}
