package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.CheckInReminderStore.FlightDay;
import com.skyhub.booking.application.CheckInReminderStore.Waiting;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class CheckInRemindersTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final FlightDay SK501 = new FlightDay("SK501", DAY);
    private static final CheckInWindow WINDOW = new CheckInWindow(Duration.ofHours(24), Duration.ofMinutes(60));

    /** In memory, with the same rule as the real one: a claimed booking no longer waits. */
    private static final class FakeStore implements CheckInReminderStore {
        final List<FlightDay> flights = new ArrayList<>();
        final List<Waiting> waiting = new ArrayList<>();
        final Set<Waiting> claimed = new HashSet<>();
        int pagesRead;
        boolean otherInstanceWins;

        @Override
        public List<FlightDay> flightsWithBookingsWaiting(LocalDate from, LocalDate to) {
            return List.copyOf(flights);
        }

        @Override
        public List<Waiting> waitingOn(FlightDay flight, int limit) {
            pagesRead++;
            return waiting.stream().filter(w -> !claimed.contains(w)).limit(limit).toList();
        }

        @Override
        public boolean claim(Waiting waiting, Instant at) {
            claimed.add(waiting);
            return !otherInstanceWins;
        }
    }

    private final FakeStore store = new FakeStore();
    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final FlightCatalog catalog = mock(FlightCatalog.class);
    private final BookingEvents events = mock(BookingEvents.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private CheckInReminders reminders;

    @BeforeEach
    void setUp() {
        store.flights.add(SK501);
        when(disruptions.findByFlightNumberAndDepartureDate(any(), any())).thenReturn(Optional.empty());
        when(catalog.departure("SK501", DAY)).thenReturn(Optional.of(NOW.plus(Duration.ofHours(5))));   // window is open
        reminders = new CheckInReminders(store, pnrs, disruptions, catalog, events, WINDOW, Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionOperations.withoutTransaction(), new CheckInReminderMetrics(registry));
    }

    private void waitingBookings(int count) {
        for (int i = 0; i < count; i++) {
            String locator = "B" + String.format("%05d", i);
            store.waiting.add(new Waiting(locator, 0));
            when(pnrs.findByLocator(locator)).thenReturn(Optional.of(pnr(locator)));
        }
    }

    private static Pnr pnr(String locator) {
        return Pnr.confirmed(locator, new ContactEmail(locator.toLowerCase() + "@example.com"), List.of(new PnrSegment("SK501", DAY)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D"))), NOW, "hash" + locator, "fp" + locator);
    }

    @Test
    void should_queue_a_reminder_for_each_booking_on_a_flight_whose_check_in_is_open() {
        waitingBookings(3);

        assertThat(reminders.sweep()).isEqualTo(3);

        verify(events, times(3)).checkInOpened(any(), eq(new PnrSegment("SK501", DAY)),
                eq(NOW.plus(Duration.ofHours(5))), eq(NOW.plus(Duration.ofHours(4))));   // closes an hour before departure
        assertThat(registry.get("skyhub.checkin.reminders").tag("outcome", "sent").counter().count()).isEqualTo(3);
    }

    @Test
    void should_not_read_a_single_booking_of_a_flight_whose_window_is_not_open_yet_or_has_closed() {
        waitingBookings(500);
        when(catalog.departure("SK501", DAY)).thenReturn(Optional.of(NOW.plus(Duration.ofHours(30))));
        assertThat(reminders.sweep()).isZero();

        when(catalog.departure("SK501", DAY)).thenReturn(Optional.of(NOW.plus(Duration.ofMinutes(30))));
        assertThat(reminders.sweep()).isZero();

        assertThat(store.pagesRead).isZero();   // cost does not depend on how many people are booked
        verify(events, never()).checkInOpened(any(), any(), any(), any());
    }

    @Test
    void should_use_the_revised_time_of_a_delayed_flight() {
        waitingBookings(1);
        when(catalog.departure("SK501", DAY)).thenReturn(Optional.of(NOW.plus(Duration.ofHours(5))));   // on schedule it would be open
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY)).thenReturn(Optional.of(
                Disruption.delayed("SK501", DAY, NOW.plus(Duration.ofHours(40)), "Weather", NOW)));

        assertThat(reminders.sweep()).isZero();   // delayed far enough that check-in has not opened
    }

    @Test
    void should_skip_a_cancelled_flight() {
        waitingBookings(1);
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY)).thenReturn(Optional.of(
                Disruption.cancelled("SK501", DAY, "Grounded", NOW)));

        assertThat(reminders.sweep()).isZero();
    }

    @Test
    void should_carry_on_with_other_flights_and_count_it_when_one_flights_time_cannot_be_checked() {
        FlightDay unknown = new FlightDay("SK777", DAY);
        store.flights.addFirst(unknown);
        when(catalog.departure("SK777", DAY)).thenThrow(new FlightCatalogUnavailableException("down", new RuntimeException()));
        waitingBookings(2);

        assertThat(reminders.sweep()).isEqualTo(2);

        assertThat(registry.get("skyhub.checkin.reminders").tag("outcome", "flight_unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void should_queue_nothing_when_another_instance_claimed_the_booking_first() {
        waitingBookings(2);
        store.otherInstanceWins = true;

        assertThat(reminders.sweep()).isZero();

        verify(events, never()).checkInOpened(any(), any(), any(), any());
    }

    @Test
    void should_not_remind_a_booking_that_was_cancelled_after_it_was_found() {
        waitingBookings(1);
        Pnr cancelled = pnr("B00000");
        cancelled.cancel();
        when(pnrs.findByLocator("B00000")).thenReturn(Optional.of(cancelled));

        assertThat(reminders.sweep()).isZero();
    }

    @Test
    void should_work_through_a_big_flight_a_page_at_a_time_and_stop_at_the_cap_leaving_the_rest_for_the_next_sweep() {
        waitingBookings(CheckInReminders.MAX_PER_SWEEP + 300);

        assertThat(reminders.sweep()).isEqualTo(CheckInReminders.MAX_PER_SWEEP);
        assertThat(store.pagesRead).isEqualTo(CheckInReminders.MAX_PER_SWEEP / CheckInReminders.PAGE_SIZE);

        assertThat(reminders.sweep()).isEqualTo(300);   // the next sweep finishes the job
        assertThat(reminders.sweep()).isZero();         // and a third finds nothing: it is done
    }
}
