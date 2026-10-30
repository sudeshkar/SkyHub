package com.skyhub.flightsearch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class FlightStatusTest {

    private static final OffsetDateTime DEPARTS = OffsetDateTime.parse("2026-11-02T08:30:00Z");

    private static FlightLeg leg() {
        return new FlightLeg("SK001", "DXB", "LHR", DEPARTS, DEPARTS.plusMinutes(470), "A380", new BigDecimal("540.00"), "USD");
    }

    @Test
    void should_describe_a_flight_nobody_has_reported_as_scheduled() {
        assertThat(leg().status()).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(FlightStatus.SCHEDULED.isCancelled()).isFalse();
        assertThat(FlightStatus.SCHEDULED.revisedDeparture()).isNull();
    }

    @Test
    void should_carry_the_new_time_of_a_delay_and_know_a_cancellation() {
        OffsetDateTime later = DEPARTS.plusHours(6);

        assertThat(FlightStatus.delayedTo(later).kind()).isEqualTo(FlightStatus.Kind.DELAYED);
        assertThat(FlightStatus.delayedTo(later).revisedDeparture()).isEqualTo(later);
        assertThat(FlightStatus.cancelled().isCancelled()).isTrue();
        assertThat(FlightStatus.cancelled().revisedDeparture()).isNull();
    }

    @Test
    void should_keep_everything_else_about_a_leg_when_its_status_is_added() {
        FlightLeg delayed = leg().withStatus(FlightStatus.delayedTo(DEPARTS.plusHours(6)));

        assertThat(delayed.flightNumber()).isEqualTo("SK001");
        assertThat(delayed.departure()).isEqualTo(DEPARTS);   // the scheduled time is untouched: the delay is reported beside it
        assertThat(delayed.status().revisedDeparture()).isEqualTo(DEPARTS.plusHours(6));
    }
}
