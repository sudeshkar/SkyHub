package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** When will this flight really leave? A disruption beats the timetable (ADR-0014, ADR-0063). */
class ExpectedDeparturesTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant SCHEDULED = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant REVISED = Instant.parse("2026-11-02T12:30:00Z");

    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);

    private ExpectedDepartures withTimetable(Optional<Instant> scheduled) {
        FlightCatalog catalog = (flight, date) -> scheduled.map(t -> new FlightInfo(t, Money.of("100.00", "USD")));
        return new ExpectedDepartures(disruptions, catalog);
    }

    @Test
    void should_use_the_timetable_when_the_flight_is_on_time() {
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any())).thenReturn(Optional.empty());

        assertThat(withTimetable(Optional.of(SCHEDULED)).of("SK501", DATE)).contains(SCHEDULED);
    }

    @Test
    void should_use_the_revised_time_when_the_flight_is_delayed_without_asking_the_timetable() {
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any()))
                .thenReturn(Optional.of(Disruption.delayed("SK501", DATE, REVISED, "weather", SCHEDULED)));
        FlightCatalog neverAsked = mock(FlightCatalog.class);

        assertThat(new ExpectedDepartures(disruptions, neverAsked).of("SK501", DATE)).contains(REVISED);
        verifyNoInteractions(neverAsked);
    }

    @Test
    void should_say_there_is_no_departure_for_a_cancelled_flight_without_asking_the_timetable() {
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any()))
                .thenReturn(Optional.of(Disruption.cancelled("SK501", DATE, "Aircraft grounded", SCHEDULED)));
        FlightCatalog neverAsked = mock(FlightCatalog.class);

        assertThat(new ExpectedDepartures(disruptions, neverAsked).of("SK501", DATE)).isEmpty();   // it will not leave: callers decide what that means
        verifyNoInteractions(neverAsked);
    }

    @Test
    void should_say_so_when_the_flight_does_not_operate() {
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withTimetable(Optional.empty()).of("SK501", DATE)).isInstanceOf(FlightNotFoundException.class);
    }
}
