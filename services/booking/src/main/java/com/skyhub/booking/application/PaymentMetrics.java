package com.skyhub.booking.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code skyhub_payments_total{outcome}}. {@code refund_failed} is the one that matters most: money was taken,
 * no booking exists, and giving it back did not work. That needs a person.
 */
@Component
public class PaymentMetrics {

    public enum Outcome {
        CAPTURED, DECLINED, UNAVAILABLE, PRICE_CHANGED, REFUNDED, REFUND_FAILED,
        /** Cancellation refunds (ADR-0016): given back, owed but the provider failed, or cancelled too late. */
        CANCEL_REFUNDED, CANCEL_REFUND_FAILED, CANCEL_NOT_REFUNDABLE;

        String tag() {
            return name().toLowerCase();
        }
    }

    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);

    public PaymentMetrics(MeterRegistry registry) {
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, Counter.builder("skyhub.payments")
                    .description("Payment attempts by outcome")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
    }

    public void record(Outcome outcome) {
        counters.get(outcome).increment();
    }
}
