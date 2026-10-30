package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class FlightEventsBackfillTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final FlightEvents events = mock(FlightEvents.class);
    private final FlightEventsBackfill backfill = new FlightEventsBackfill(disruptions, events,
            Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction());

    @Test
    void should_republish_every_disruption_from_yesterday_on_so_a_search_service_that_missed_them_learns_them() {
        Disruption today = Disruption.cancelled("SK001", TODAY, "Grounded", NOW);
        Disruption later = Disruption.delayed("SK002", TODAY.plusDays(90), NOW.plusSeconds(3600), "Weather", NOW);
        when(disruptions.findByDepartureDateGreaterThanEqual(TODAY.minusDays(1))).thenReturn(List.of(today, later));

        int published = backfill.run();

        assertThat(published).isEqualTo(2);
        verify(events).flightDisrupted(today);
        verify(events).flightDisrupted(later);
    }

    @Test
    void should_do_nothing_when_there_are_no_disruptions() {
        when(disruptions.findByDepartureDateGreaterThanEqual(any())).thenReturn(List.of());

        assertThat(backfill.run()).isZero();

        verify(events, never()).flightDisrupted(any());
    }
}
