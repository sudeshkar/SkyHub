package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.DisruptionType;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisruptionServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-01T08:00:00Z");
    private static final Instant REVISED = Instant.parse("2026-11-02T14:30:00Z");

    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final BookingEvents events = mock(BookingEvents.class);
    private final FlightEvents flightEvents = mock(FlightEvents.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private DisruptionService service;

    @BeforeEach
    void setUp() {
        when(disruptions.save(any(Disruption.class))).thenAnswer(call -> call.getArgument(0));
        when(disruptions.findByFlightNumberAndDepartureDate(any(), any())).thenReturn(Optional.empty());
        when(pnrs.findActiveOnFlight("SK501", DATE)).thenReturn(bookings(42));
        service = new DisruptionService(disruptions, pnrs, Clock.fixed(NOW, ZoneOffset.UTC), new DisruptionMetrics(registry),
                events, flightEvents, org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    private static List<Pnr> bookings(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> Pnr.confirmed("B" + String.format("%05d", i),
                new ContactEmail("p" + i + "@example.com"), List.of(new PnrSegment("SK501", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D"))), NOW, "hash" + i, "fingerprint" + i)).toList();
    }

    private static RecordDisruptionCommand delay(Instant revised, String reason) {
        return new RecordDisruptionCommand("SK501", DATE, DisruptionType.DELAYED, revised, reason, "priya.ops");
    }

    @Test
    void should_record_a_delay_and_report_how_many_bookings_are_affected() {
        int affected = service.record(delay(REVISED, "  Late inbound aircraft  "));

        assertThat(affected).isEqualTo(42);
        var saved = org.mockito.ArgumentCaptor.forClass(Disruption.class);
        verify(disruptions).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(DisruptionType.DELAYED);
        assertThat(saved.getValue().getRevisedDeparture()).isEqualTo(REVISED);
        assertThat(saved.getValue().getReason()).isEqualTo("Late inbound aircraft");
        assertThat(saved.getValue().getRecordedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getRecordedBy()).isEqualTo("priya.ops");
        assertThat(registry.counter("skyhub.disruptions", "type", "delayed").count()).isEqualTo(1);
    }

    @Test
    void should_list_the_bookings_on_a_flight_with_only_what_re_accommodation_needs() {
        Pnr twoLegs = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK001", DATE), new PnrSegment("SK501", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A")),
                        new PnrPassenger("Anil", "Perera", List.of("11E", "24B"))), NOW, "hash", "fingerprint");
        when(pnrs.findActiveOnFlight("SK501", DATE)).thenReturn(List.of(twoLegs));

        List<AffectedBooking> affected = service.affectedBookings("SK501", DATE);

        assertThat(affected).containsExactly(new AffectedBooking("ABC234", 2, 1,
                List.of(new PnrSegment("SK001", DATE), new PnrSegment("SK501", DATE))));
    }

    @Test
    void should_list_nothing_for_a_flight_nobody_is_booked_on() {
        when(pnrs.findActiveOnFlight("SK900", DATE)).thenReturn(List.of());

        assertThat(service.affectedBookings("SK900", DATE)).isEmpty();
    }

    @Test
    void should_tell_every_affected_booking_in_the_same_transaction_as_the_record() {
        List<Pnr> affected = bookings(3);
        when(pnrs.findActiveOnFlight("SK501", DATE)).thenReturn(affected);

        service.record(delay(REVISED, "Late inbound aircraft"));

        var saved = org.mockito.ArgumentCaptor.forClass(Disruption.class);
        var order = org.mockito.Mockito.inOrder(disruptions, events);
        order.verify(disruptions).save(saved.capture());
        affected.forEach(pnr -> order.verify(events).bookingDisrupted(pnr, saved.getValue()));
    }

    @Test
    void should_publish_the_flight_s_new_state_once_in_the_same_transaction_so_search_can_follow() {
        service.record(delay(REVISED, "Late inbound aircraft"));

        var saved = org.mockito.ArgumentCaptor.forClass(Disruption.class);
        var order = org.mockito.Mockito.inOrder(disruptions, flightEvents);
        order.verify(disruptions).save(saved.capture());
        order.verify(flightEvents).flightDisrupted(saved.getValue());
    }

    @Test
    void should_not_publish_again_when_the_same_disruption_is_recorded_twice() {
        Disruption earlier = Disruption.delayed("SK501", DATE, REVISED, "Late inbound aircraft", NOW.minusSeconds(600));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(earlier));

        service.record(delay(REVISED, "Late inbound aircraft"));

        verify(flightEvents, never()).flightDisrupted(any());
    }

    @Test
    void should_publish_a_change_so_search_hears_that_a_delay_became_a_cancellation() {
        Disruption earlier = Disruption.delayed("SK501", DATE, REVISED, "Late inbound aircraft", NOW.minusSeconds(600));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(earlier));

        service.record(new RecordDisruptionCommand("SK501", DATE, DisruptionType.CANCELLED, null, "Aircraft grounded", "priya.ops"));

        verify(flightEvents).flightDisrupted(earlier);
    }

    @Test
    void should_tell_nobody_when_the_same_disruption_is_recorded_again() {
        Disruption earlier = Disruption.delayed("SK501", DATE, REVISED, "Late inbound aircraft", NOW.minusSeconds(600));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(earlier));

        int affected = service.record(delay(REVISED, "  Late inbound aircraft "));

        assertThat(affected).isEqualTo(42); // the size of it is still reported
        verify(events, never()).bookingDisrupted(any(), any());
    }

    @Test
    void should_tell_everyone_again_when_a_delay_is_corrected_or_becomes_a_cancellation() {
        Disruption earlier = Disruption.delayed("SK501", DATE, REVISED, "Late inbound aircraft", NOW.minusSeconds(600));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(earlier));
        when(pnrs.findActiveOnFlight("SK501", DATE)).thenReturn(bookings(2));

        service.record(delay(REVISED.plusSeconds(3600), "Late inbound aircraft"));

        verify(events, org.mockito.Mockito.times(2)).bookingDisrupted(any(), eq(earlier));
    }

    @Test
    void should_tell_nobody_when_the_record_could_not_be_saved() {
        when(disruptions.save(any(Disruption.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_disruption_flight"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_disruption_flight"));

        assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> service.record(delay(REVISED, "x")));

        verify(events, never()).bookingDisrupted(any(), any());
    }

    @Test
    void should_record_a_cancellation() {
        service.record(new RecordDisruptionCommand("SK501", DATE, DisruptionType.CANCELLED, null, null, "priya.ops"));

        var saved = org.mockito.ArgumentCaptor.forClass(Disruption.class);
        verify(disruptions).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(DisruptionType.CANCELLED);
        assertThat(saved.getValue().getRevisedDeparture()).isNull();
        assertThat(saved.getValue().getReason()).isEmpty();
    }

    @Test
    void should_replace_the_earlier_record_for_the_same_flight_instead_of_adding_another() {
        Disruption earlier = Disruption.delayed("SK501", DATE, REVISED, "Late aircraft", NOW.minusSeconds(600));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(earlier));

        service.record(new RecordDisruptionCommand("SK501", DATE, DisruptionType.CANCELLED, null, "Aircraft grounded", "priya.ops"));

        assertThat(earlier.getType()).isEqualTo(DisruptionType.CANCELLED);
        assertThat(earlier.getRevisedDeparture()).isNull();
        assertThat(earlier.getReason()).isEqualTo("Aircraft grounded");
        assertThat(earlier.getRecordedAt()).isEqualTo(NOW);
        verify(disruptions).save(earlier);
    }

    @Test
    void should_update_the_record_the_other_operator_created_first_when_both_record_at_once() {
        Disruption winner = Disruption.delayed("SK501", DATE, REVISED, "First", NOW.minusSeconds(1));
        when(disruptions.save(any(Disruption.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_disruption_flight"))
                .thenAnswer(call -> call.getArgument(0));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        service.record(delay(REVISED.plusSeconds(1800), "Second"));

        assertThat(winner.getReason()).isEqualTo("Second");
        assertThat(winner.getRevisedDeparture()).isEqualTo(REVISED.plusSeconds(1800));
    }

    @Test
    void should_refuse_requests_that_make_no_sense_and_save_nothing() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.record(delay(null, "x")))
                .withMessageContaining("revised departure");
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(
                        () -> service.record(new RecordDisruptionCommand("SK501", DATE, DisruptionType.CANCELLED, REVISED, "x", "priya.ops")))
                .withMessageContaining("cancelled");
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.record(delay(REVISED, "x".repeat(201))))
                .withMessageContaining("200");
        verify(disruptions, never()).save(any());
    }

    @Test
    void should_list_only_the_disrupted_flights_of_a_booking_with_their_position() {
        Pnr pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", DATE), new PnrSegment("SK901", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A"))), NOW, "hash", "fingerprint");
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        when(disruptions.findByFlightNumberAndDepartureDate("SK901", DATE))
                .thenReturn(Optional.of(Disruption.delayed("SK901", DATE, REVISED, "Weather", NOW)));

        List<BookingDisruption> items = service.forBooking("ABC234");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().segmentIndex()).isEqualTo(1);
        assertThat(items.getFirst().flightNumber()).isEqualTo("SK901");
        assertThat(items.getFirst().revisedDeparture()).isEqualTo(REVISED);
        assertThat(items.getFirst().reason()).isEqualTo("Weather");
    }

    @Test
    void should_not_know_an_unknown_booking() {
        when(pnrs.findByLocator(eq("ZZZZZZ"))).thenReturn(Optional.empty());

        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.forBooking("ZZZZZZ"));
    }
}
