package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CheckInWindowTest {

    private static final Instant DEPARTURE = Instant.parse("2026-11-02T10:00:00Z");
    private final CheckInWindow window = new CheckInWindow(Duration.ofHours(24), Duration.ofMinutes(60));

    @Test
    void should_be_open_between_24_hours_and_60_minutes_before_departure() {
        assertThatCode(() -> window.requireOpen(DEPARTURE, DEPARTURE.minus(Duration.ofHours(24)))).doesNotThrowAnyException();
        assertThatCode(() -> window.requireOpen(DEPARTURE, DEPARTURE.minus(Duration.ofHours(5)))).doesNotThrowAnyException();
        assertThatCode(() -> window.requireOpen(DEPARTURE, DEPARTURE.minus(Duration.ofMinutes(60).plusSeconds(1))))
                .doesNotThrowAnyException();
    }

    @Test
    void should_say_when_it_opens_if_asked_too_early() {
        assertThatExceptionOfType(CheckInClosedException.class)
                .isThrownBy(() -> window.requireOpen(DEPARTURE, DEPARTURE.minus(Duration.ofHours(24)).minusSeconds(1)))
                .withMessageContaining("opens")
                .withMessageContaining("2026-11-01T10:00:00Z");
    }

    @Test
    void should_be_closed_from_60_minutes_before_departure() {
        assertThatExceptionOfType(CheckInClosedException.class)
                .isThrownBy(() -> window.requireOpen(DEPARTURE, DEPARTURE.minus(Duration.ofMinutes(60))))
                .withMessageContaining("closed");
        assertThatExceptionOfType(CheckInClosedException.class)
                .isThrownBy(() -> window.requireOpen(DEPARTURE, DEPARTURE.plusSeconds(1)))
                .withMessageContaining("closed");
    }

    @Test
    void should_refuse_a_window_that_closes_before_it_opens() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CheckInWindow(Duration.ofMinutes(30), Duration.ofMinutes(60)));
    }
}
