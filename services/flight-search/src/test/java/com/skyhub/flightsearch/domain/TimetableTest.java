package com.skyhub.flightsearch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimetableTest {

    private static final FlightSchedule SK501 = schedule("SK501", "CMB", "DXB");
    private static final FlightSchedule SK001 = schedule("SK001", "DXB", "LHR");
    private static final FlightSchedule SK431 = schedule("SK431", "DXB", "SIN");

    private final Timetable timetable = Timetable.of(List.of(SK501, SK001, SK431));

    @Test
    void should_index_flights_by_origin() {
        assertThat(timetable.departingFrom("DXB")).containsExactlyInAnyOrder(SK001, SK431);
    }

    @Test
    void should_index_flights_by_destination() {
        assertThat(timetable.arrivingAt("DXB")).containsExactly(SK501);
    }

    @Test
    void should_find_a_flight_by_number() {
        assertThat(timetable.flight("SK431")).contains(SK431);
        assertThat(timetable.flight("SK999")).isEmpty();
    }

    @Test
    void should_return_empty_lists_for_unknown_airports() {
        assertThat(timetable.departingFrom("XXX")).isEmpty();
        assertThat(timetable.arrivingAt("XXX")).isEmpty();
    }

    @Test
    void should_be_immutable() {
        assertThat(timetable.departingFrom("DXB")).isUnmodifiable();
        assertThat(timetable.size()).isEqualTo(3);
    }

    private static FlightSchedule schedule(String number, String from, String to) {
        return new FlightSchedule(number, from, to, LocalTime.NOON, 60, "B777", BigDecimal.TEN, "USD", "1234567");
    }
}
