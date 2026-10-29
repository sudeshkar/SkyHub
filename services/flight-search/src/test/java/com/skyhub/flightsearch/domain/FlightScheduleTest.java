package com.skyhub.flightsearch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FlightScheduleTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate TUESDAY = MONDAY.plusDays(1);

    @Test
    void should_operate_only_on_listed_days() {
        var monWedFri = schedule("135", LocalTime.of(11, 0), 840);

        assertThat(monWedFri.operatesOn(MONDAY)).isTrue();
        assertThat(monWedFri.operatesOn(TUESDAY)).isFalse();
    }

    @Test
    void should_build_leg_with_utc_departure_on_given_date() {
        var leg = schedule("1234567", LocalTime.of(2, 0), 290).legOn(MONDAY);

        assertThat(leg.departure()).isEqualTo(OffsetDateTime.of(2026, 11, 2, 2, 0, 0, 0, ZoneOffset.UTC));
        assertThat(leg.arrival()).isEqualTo(OffsetDateTime.of(2026, 11, 2, 6, 50, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void should_compute_arrival_after_midnight() {
        var leg = schedule("1234567", LocalTime.of(10, 30), 840).legOn(MONDAY);

        assertThat(leg.arrival()).isEqualTo(OffsetDateTime.of(2026, 11, 3, 0, 30, 0, 0, ZoneOffset.UTC));
    }

    private static FlightSchedule schedule(String days, LocalTime departure, int minutes) {
        return new FlightSchedule("SK999", "AAA", "BBB", departure, minutes, "B777",
                new BigDecimal("100.00"), "USD", days);
    }
}
