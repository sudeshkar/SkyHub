package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skyhub.booking.domain.BoardingWindow.State;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Boarding opens and closes at set distances before departure (ADR-0063), by the time the flight will really leave. */
class BoardingWindowTest {

    private static final Instant DEPARTURE = Instant.parse("2026-11-02T10:00:00Z");
    private final BoardingWindow window = new BoardingWindow(Duration.ofMinutes(45), Duration.ofMinutes(10));

    @Test
    void should_not_be_open_before_it_opens() {
        assertThat(window.stateAt(DEPARTURE, DEPARTURE.minus(Duration.ofMinutes(46)))).isEqualTo(State.NOT_YET_OPEN);
    }

    @Test
    void should_be_open_from_the_opening_instant_up_to_the_closing_instant() {
        assertThat(window.stateAt(DEPARTURE, DEPARTURE.minus(Duration.ofMinutes(45)))).isEqualTo(State.OPEN);
        assertThat(window.stateAt(DEPARTURE, DEPARTURE.minus(Duration.ofMinutes(11)))).isEqualTo(State.OPEN);
    }

    @Test
    void should_be_closed_from_the_closing_instant_on_even_after_departure() {
        assertThat(window.stateAt(DEPARTURE, DEPARTURE.minus(Duration.ofMinutes(10)))).isEqualTo(State.CLOSED);
        assertThat(window.stateAt(DEPARTURE, DEPARTURE.plus(Duration.ofHours(3)))).isEqualTo(State.CLOSED);
    }

    @Test
    void should_follow_a_delayed_departure_because_the_caller_passes_the_revised_time() {
        Instant revised = DEPARTURE.plus(Duration.ofHours(2));
        assertThat(window.stateAt(revised, DEPARTURE.minus(Duration.ofMinutes(20)))).isEqualTo(State.NOT_YET_OPEN);
    }

    @Test
    void should_refuse_a_window_that_closes_before_it_opens_or_has_no_length() {
        assertThatThrownBy(() -> new BoardingWindow(Duration.ofMinutes(10), Duration.ofMinutes(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardingWindow(Duration.ofMinutes(10), Duration.ofMinutes(20))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoardingWindow(Duration.ofMinutes(10), Duration.ofMinutes(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
