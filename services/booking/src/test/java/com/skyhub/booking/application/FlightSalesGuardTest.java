package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightCancelledException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FlightSalesGuardTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final FlightSalesGuard guard = new FlightSalesGuard(disruptions);

    @Test
    void should_let_an_ordinary_flight_be_sold() {
        when(disruptions.findByFlightNumberAndDepartureDate(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> guard.requireSellable(List.of(new PnrSegment("SK501", DAY)))).doesNotThrowAnyException();
    }

    @Test
    void should_still_sell_a_delayed_flight_because_the_traveller_can_choose_it_knowing_the_new_time() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY))
                .thenReturn(Optional.of(Disruption.delayed("SK501", DAY, NOW.plusSeconds(86_400), "Weather", NOW)));

        assertThatCode(() -> guard.requireSellable(List.of(new PnrSegment("SK501", DAY)))).doesNotThrowAnyException();
    }

    @Test
    void should_refuse_a_cancelled_flight_and_say_which() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY))
                .thenReturn(Optional.of(Disruption.cancelled("SK501", DAY, "Aircraft grounded", NOW)));

        assertThatExceptionOfType(FlightCancelledException.class)
                .isThrownBy(() -> guard.requireSellable(List.of(new PnrSegment("SK501", DAY))))
                .withMessageContaining("SK501").withMessageContaining("cancelled");
    }

    @Test
    void should_refuse_a_trip_when_any_one_of_its_flights_is_cancelled() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK001", DAY)).thenReturn(Optional.empty());
        when(disruptions.findByFlightNumberAndDepartureDate("SK700", DAY))
                .thenReturn(Optional.of(Disruption.cancelled("SK700", DAY, "Crew", NOW)));

        assertThatExceptionOfType(FlightCancelledException.class)
                .isThrownBy(() -> guard.requireSellable(List.of(new PnrSegment("SK001", DAY), new PnrSegment("SK700", DAY))))
                .withMessageContaining("SK700");
    }

    @Test
    void should_make_one_lookup_per_flight_and_none_for_a_flight_repeated() {
        when(disruptions.findByFlightNumberAndDepartureDate(any(), any())).thenReturn(Optional.empty());

        guard.requireSellable(List.of(new PnrSegment("SK001", DAY), new PnrSegment("SK700", DAY), new PnrSegment("SK001", DAY)));

        verify(disruptions, times(2)).findByFlightNumberAndDepartureDate(any(), any());
    }
}
