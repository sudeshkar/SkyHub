package com.skyhub.flightsearch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.flightsearch.domain.FlightNotFoundException;
import com.skyhub.flightsearch.domain.FlightSchedule;
import com.skyhub.flightsearch.domain.Timetable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlightLookupServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    private final Timetable timetable = Timetable.of(List.of(
            new FlightSchedule("SK411", "DWC", "SYD", LocalTime.of(11, 0), 840, "A380", BigDecimal.TEN, "USD", "135")));
    private final FlightLookupService service = new FlightLookupService(() -> timetable);

    @Test
    void should_return_the_dated_flight_when_it_operates() {
        assertThat(service.departure("SK411", MONDAY).departure().toLocalDate()).isEqualTo(MONDAY);
    }

    @Test
    void should_reject_a_day_the_flight_does_not_operate() {
        assertThatExceptionOfType(FlightNotFoundException.class).isThrownBy(() -> service.departure("SK411", MONDAY.plusDays(1)));
    }

    @Test
    void should_reject_an_unknown_flight() {
        assertThatExceptionOfType(FlightNotFoundException.class).isThrownBy(() -> service.departure("SK999", MONDAY));
    }
}
