package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** ADR-0016: full refund if the airline cancelled, or cancelling 24 hours or more before departure; else none. */
class RefundPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private final RefundPolicy policy = new RefundPolicy();

    @Test
    void should_refund_in_full_when_cancelling_well_before_departure() {
        assertThat(policy.decide(NOW.plus(Duration.ofDays(3)), false, NOW))
                .isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_refund_in_full_exactly_24_hours_before_departure() {
        assertThat(policy.decide(NOW.plus(Duration.ofHours(24)), false, NOW))
                .isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_refuse_a_refund_one_second_inside_24_hours() {
        assertThat(policy.decide(NOW.plus(Duration.ofHours(24)).minusSeconds(1), false, NOW))
                .isEqualTo(RefundPolicy.Decision.NO_REFUND);
    }

    @Test
    void should_refuse_a_refund_after_departure() {
        assertThat(policy.decide(NOW.minus(Duration.ofHours(2)), false, NOW))
                .isEqualTo(RefundPolicy.Decision.NO_REFUND);
    }

    @Test
    void should_refund_in_full_when_the_airline_cancelled_even_at_the_gate() {
        assertThat(policy.decide(NOW.plus(Duration.ofMinutes(10)), true, NOW))
                .isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_refund_in_full_when_the_airline_cancelled_even_after_departure_time() {
        assertThat(policy.decide(NOW.minus(Duration.ofHours(5)), true, NOW))
                .isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }
}
