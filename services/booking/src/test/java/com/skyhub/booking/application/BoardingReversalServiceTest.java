package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.NotBoardedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Taking back a boarding (ADR-0064): a wrong scan is corrected by a person with authority, with a reason, and the correction is kept.
 * The passenger stays checked in, so they can board again with the same pass.
 */
class BoardingReversalServiceTest {

    private static final Instant SCANNED = Instant.parse("2026-11-03T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-03T09:05:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FakeBoardedPassengers boarded = new FakeBoardedPassengers();
    private BoardingReversalService service;

    @BeforeEach
    void setUp() {
        service = new BoardingReversalService(boarded, Clock.fixed(NOW, ZoneOffset.UTC), new BoardingPassMetrics(registry));
        boarded.board("ABC234", 0, 1, "gate.one", SCANNED);
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.boarding_pass.reversals").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_take_the_boarding_back_and_keep_who_scanned_who_corrected_when_and_why() {
        service.undo("ABC234", 0, 1, "  Scanned the wrong passenger  ", "priya.ops");

        assertThat(boarded.isBoarded("ABC234", 0, 1)).isFalse();
        assertThat(boarded.reversals).containsExactly(new FakeBoardedPassengers.Reversal(
                new FakeBoardedPassengers.Row("ABC234", 0, 1, "gate.one", SCANNED), "priya.ops", "Scanned the wrong passenger", NOW));
        assertThat(count("reversed")).isEqualTo(1);
    }

    @Test
    void should_let_the_passenger_board_again_after_the_correction() {
        service.undo("ABC234", 0, 1, "wrong passenger", "priya.ops");

        assertThat(boarded.board("ABC234", 0, 1, "gate.two", NOW)).isTrue();
    }

    @Test
    void should_say_so_when_that_passenger_has_not_boarded_and_change_nothing() {
        assertThatThrownBy(() -> service.undo("ABC234", 0, 0, "wrong passenger", "priya.ops")).isInstanceOf(NotBoardedException.class);

        assertThat(boarded.rows).hasSize(1);
        assertThat(boarded.reversals).isEmpty();
        assertThat(count("not_boarded")).isEqualTo(1);
    }

    @Test
    void should_undo_once_so_asking_twice_reports_not_boarded_rather_than_recording_two_reversals() {
        service.undo("ABC234", 0, 1, "wrong passenger", "priya.ops");

        assertThatThrownBy(() -> service.undo("ABC234", 0, 1, "wrong passenger", "priya.ops")).isInstanceOf(NotBoardedException.class);
        assertThat(boarded.reversals).hasSize(1);
    }

    @Test
    void should_require_a_reason_of_reasonable_length_and_change_nothing_without_one() {
        assertThatThrownBy(() -> service.undo("ABC234", 0, 1, "   ", "priya.ops")).isInstanceOf(InvalidBookingException.class);
        assertThatThrownBy(() -> service.undo("ABC234", 0, 1, null, "priya.ops")).isInstanceOf(InvalidBookingException.class);
        assertThatThrownBy(() -> service.undo("ABC234", 0, 1, "x".repeat(201), "priya.ops")).isInstanceOf(InvalidBookingException.class);

        assertThat(boarded.isBoarded("ABC234", 0, 1)).isTrue();
        assertThat(boarded.reversals).isEmpty();
    }
}
