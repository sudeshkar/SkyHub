package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a flight's boarding looks like to operations (ADR-0065): how many are checked in, who has boarded, and how many are still to come. */
class FlightBoardingTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private static final Instant AT = Instant.parse("2026-11-03T09:00:00Z");

    private static FlightBoarding.Boarding boarding(String locator, int passenger) {
        return new FlightBoarding.Boarding(locator, 0, passenger, "gate.one", AT);
    }

    @Test
    void should_count_who_has_boarded_and_how_many_checked_in_are_still_to_come() {
        var flight = new FlightBoarding("SK501", DATE, 5, List.of(boarding("ABC234", 0), boarding("ABC234", 1)));

        assertThat(flight.boarded()).isEqualTo(2);
        assertThat(flight.waiting()).isEqualTo(3);
    }

    @Test
    void should_have_nobody_waiting_when_everyone_checked_in_has_boarded() {
        assertThat(new FlightBoarding("SK501", DATE, 1, List.of(boarding("ABC234", 0))).waiting()).isZero();
    }

    @Test
    void should_never_report_a_negative_waiting_count_even_if_the_counts_disagree() {
        // A passenger can be checked out only until boarding, and checked in again; a count taken a moment apart must not go below zero.
        assertThat(new FlightBoarding("SK501", DATE, 0, List.of(boarding("ABC234", 0))).waiting()).isZero();
    }

    @Test
    void should_describe_a_flight_nobody_has_boarded_as_empty_not_as_an_error() {
        var flight = new FlightBoarding("SK501", DATE, 0, List.of());

        assertThat(flight.boarded()).isZero();
        assertThat(flight.waiting()).isZero();
        assertThat(flight.boardings()).isEmpty();
    }
}
