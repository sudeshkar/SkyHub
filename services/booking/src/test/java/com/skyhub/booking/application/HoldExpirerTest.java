package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.persistence.SeatInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class HoldExpirerTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    private final SeatInventoryRepository seats = mock(SeatInventoryRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HoldExpirer expirer = new HoldExpirer(seats, Clock.fixed(NOW, ZoneOffset.UTC),
            TransactionOperations.withoutTransaction(), new HoldMetrics(registry));

    @Test
    void should_free_holds_that_ended_before_now_and_count_them() {
        when(seats.expireHolds(NOW)).thenReturn(3);

        expirer.expire();

        verify(seats).expireHolds(NOW);
        assertThat(registry.counter("skyhub.seat.holds", "outcome", "expired").count()).isEqualTo(3);
    }

    @Test
    void should_count_nothing_when_nothing_expired() {
        when(seats.expireHolds(NOW)).thenReturn(0);

        expirer.expire();

        assertThat(registry.counter("skyhub.seat.holds", "outcome", "expired").count()).isZero();
    }

    @Test
    void should_survive_a_database_failure_and_try_again_next_time() {
        when(seats.expireHolds(NOW)).thenThrow(new IllegalStateException("database down")).thenReturn(1);

        expirer.expire(); // must not throw: a failing sweep must not stop the schedule
        expirer.expire();

        assertThat(registry.counter("skyhub.seat.holds", "outcome", "expired").count()).isEqualTo(1);
        verify(seats, never()).deleteAll();
    }
}
