package com.skyhub.booking.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The fare rule for cancelling a paid booking (ADR-0016): full refund if the airline cancelled a flight on it, or
 * if the first departure is 24 hours or more away; otherwise nothing. Pure: no clock, database or provider, so a
 * richer policy can replace it without touching the cancel flow.
 */
public class RefundPolicy {

    public enum Decision {
        FULL_REFUND, NO_REFUND
    }

    static final Duration FREE_CANCELLATION_NOTICE = Duration.ofHours(24);

    public Decision decide(Instant firstDeparture, boolean airlineCancelled, Instant now) {
        if (airlineCancelled || !now.plus(FREE_CANCELLATION_NOTICE).isAfter(firstDeparture)) {
            return Decision.FULL_REFUND;
        }
        return Decision.NO_REFUND;
    }
}
