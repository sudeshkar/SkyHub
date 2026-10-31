package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.CheckIn;
import com.skyhub.booking.domain.CheckInClosedException;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.NotCheckedInException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightCancelledException;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.security.HmacBoardingPassSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

class CheckInServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");
    private static final Instant DEPARTURE = NOW.plus(Duration.ofHours(5)); // inside the 24 h .. 60 min window

    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final CheckInRepository checkIns = mock(CheckInRepository.class);
    private final BoardingSequence sequence = mock(BoardingSequence.class);
    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HmacBoardingPassSigner signer = new HmacBoardingPassSigner("0123456789abcdef0123456789abcdef".getBytes());
    private final List<CheckIn> stored = new ArrayList<>();
    private Optional<Instant> departure = Optional.of(DEPARTURE);
    private Pnr pnr;
    private CheckInService service;

    @BeforeEach
    void setUp() {
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", DATE), new PnrSegment("SK901", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A")),
                        new PnrPassenger("Raj", "Perera", List.of("11E", "24B"))),
                NOW.minusSeconds(3600), "key-hash", "fingerprint");
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        when(checkIns.findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(anyString(), anyInt()))
                .thenAnswer(call -> stored.stream()
                        .filter(c -> c.getLocator().equals(call.getArgument(0)) && c.getSegmentIndex() == (int) call.getArgument(1))
                        .sorted(java.util.Comparator.comparingInt(CheckIn::getPassengerIndex)).toList());
        when(checkIns.saveAllAndFlush(any())).thenAnswer(call -> {
            List<CheckIn> rows = new ArrayList<>();
            call.<Iterable<CheckIn>>getArgument(0).forEach(rows::add);
            stored.addAll(rows);
            return rows;
        });
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(2))).thenReturn(7);
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any())).thenReturn(Optional.empty());
        service = new CheckInService(pnrs, checkIns, sequence, disruptions, (flight, date) -> departure.map(d -> new FlightInfo(d, com.skyhub.booking.domain.Money.of("100.00", "USD"))), signer,
                Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction(),
                new CheckInWindow(Duration.ofHours(24), Duration.ofMinutes(60)), new CheckInMetrics(registry));
    }

    private double count(String outcome) {
        return registry.counter("skyhub.check_ins", "outcome", outcome).count();
    }

    @Test
    void should_check_in_every_passenger_on_the_flight_with_consecutive_sequence_numbers() {
        CheckInResult result = service.checkIn("ABC234", 0);

        assertThat(result.created()).isTrue();
        assertThat(result.passes()).extracting(BoardingPass::passengerName).containsExactly("PERERA/PRIYA", "PERERA/RAJ");
        assertThat(result.passes()).extracting(BoardingPass::seatNumber).containsExactly("11D", "11E");
        assertThat(result.passes()).extracting(BoardingPass::sequence).containsExactly(7, 8);
        assertThat(result.passes()).allSatisfy(pass -> {
            assertThat(pass.flightNumber()).isEqualTo("SK501");
            assertThat(pass.locator()).isEqualTo("ABC234");
            assertThat(pass.departureDate()).isEqualTo(DATE);
        });
        assertThat(stored).hasSize(2);
        assertThat(count("checked_in")).isEqualTo(1);
    }

    @Test
    void should_sign_each_boarding_pass_so_a_gate_can_verify_it() {
        BoardingPass pass = service.checkIn("ABC234", 0).passes().getFirst();

        assertThat(signer.verify(pass.barcode())).isTrue();
        assertThat(pass.barcode()).contains("PERERA/PRIYA").contains("ABC234").contains("SK501").contains("11D");
        assertThat(signer.verify(pass.barcode().replace("11D", "1A"))).isFalse();
    }

    @Test
    void should_return_the_same_passes_when_checking_in_again_without_renumbering() {
        CheckInResult first = service.checkIn("ABC234", 0);

        CheckInResult again = service.checkIn("ABC234", 0);

        assertThat(again.created()).isFalse();
        assertThat(again.passes()).isEqualTo(first.passes());
        verify(sequence).reserve(anyString(), any(), anyInt()); // only the first time
        assertThat(count("replayed")).isEqualTo(1);
    }

    @Test
    void should_keep_flights_of_one_booking_independent() {
        when(sequence.reserve(eq("SK901"), eq(DATE), eq(2))).thenReturn(1);

        service.checkIn("ABC234", 1);

        assertThat(service.boardingPasses("ABC234", 1).passes()).extracting(BoardingPass::seatNumber).containsExactly("24A", "24B");
        assertThatExceptionOfType(NotCheckedInException.class).isThrownBy(() -> service.boardingPasses("ABC234", 0));
    }

    @Test
    void should_refuse_check_in_outside_the_window_and_save_nothing() {
        departure = Optional.of(NOW.plus(Duration.ofHours(30)));
        assertThatExceptionOfType(CheckInClosedException.class).isThrownBy(() -> service.checkIn("ABC234", 0))
                .withMessageContaining("opens");

        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));
        assertThatExceptionOfType(CheckInClosedException.class).isThrownBy(() -> service.checkIn("ABC234", 0))
                .withMessageContaining("closed");

        verify(sequence, never()).reserve(anyString(), any(), anyInt());
        assertThat(stored).isEmpty();
        assertThat(count("refused")).isEqualTo(2);
    }

    @Test
    void should_still_return_the_passes_after_the_window_closes_because_they_already_exist() {
        service.checkIn("ABC234", 0);
        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));

        assertThat(service.checkIn("ABC234", 0).passes()).hasSize(2);
    }

    @Test
    void should_work_out_the_window_from_the_revised_time_of_a_delayed_flight() {
        // The timetable says it leaves in 30 minutes (window closed), but it has been delayed to 5 hours from now.
        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(
                Disruption.delayed("SK501", DATE, NOW.plus(Duration.ofHours(5)), "Late aircraft", NOW)));

        assertThat(service.checkIn("ABC234", 0).created()).isTrue();
    }

    @Test
    void should_keep_check_in_closed_until_the_delayed_flights_window_opens() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(
                Disruption.delayed("SK501", DATE, NOW.plus(Duration.ofHours(30)), "Late aircraft", NOW)));

        assertThatExceptionOfType(CheckInClosedException.class).isThrownBy(() -> service.checkIn("ABC234", 0))
                .withMessageContaining("opens");
        verify(sequence, never()).reserve(anyString(), any(), anyInt());
    }

    @Test
    void should_refuse_check_in_for_a_cancelled_flight() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(
                Disruption.cancelled("SK501", DATE, "Aircraft grounded", NOW)));

        assertThatExceptionOfType(FlightCancelledException.class).isThrownBy(() -> service.checkIn("ABC234", 0))
                .withMessageContaining("SK501");
        verify(sequence, never()).reserve(anyString(), any(), anyInt());
        assertThat(count("refused")).isEqualTo(1);
    }

    @Test
    void should_not_disturb_the_other_flight_of_the_booking() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(
                Disruption.cancelled("SK501", DATE, "Aircraft grounded", NOW)));
        when(sequence.reserve(eq("SK901"), eq(DATE), eq(2))).thenReturn(1);

        assertThat(service.checkIn("ABC234", 1).passes()).hasSize(2);
    }

    @Test
    void should_refuse_a_cancelled_booking() {
        pnr.cancel();

        assertThatExceptionOfType(BookingCancelledException.class).isThrownBy(() -> service.checkIn("ABC234", 0));
        assertThatExceptionOfType(BookingCancelledException.class).isThrownBy(() -> service.boardingPasses("ABC234", 0));
    }

    @Test
    void should_refuse_a_flight_the_booking_does_not_have() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.checkIn("ABC234", 2));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.checkIn("ABC234", -1));
    }

    @Test
    void should_refuse_when_the_flight_no_longer_operates() {
        departure = Optional.empty();

        assertThatExceptionOfType(FlightNotFoundException.class).isThrownBy(() -> service.checkIn("ABC234", 0));
    }

    @Test
    void should_not_know_an_unknown_booking() {
        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.checkIn("ZZZZZZ", 0));
    }

    @Test
    void should_treat_a_lost_race_as_a_replay_of_the_winners_passes() {
        doAnswer(call -> {
            // Another request checked this booking in between our read and our write.
            stored.add(CheckIn.of("ABC234", 0, 0, 3, NOW));
            stored.add(CheckIn.of("ABC234", 0, 1, 4, NOW));
            throw new DataIntegrityViolationException("uq_check_in");
        }).when(checkIns).saveAllAndFlush(any());

        CheckInResult result = service.checkIn("ABC234", 0);

        assertThat(result.created()).isFalse();
        assertThat(result.passes()).extracting(BoardingPass::sequence).containsExactly(3, 4);
        assertThat(count("replayed")).isEqualTo(1);
    }

    // ---- checking out (ADR-0055) -------------------------------------------------------------------------------------

    /** The table, the way delete-by-flight would leave it. */
    private void deletingRemovesFromStored() {
        when(checkIns.deleteByLocatorAndSegmentIndexAndPassengerIndexIn(anyString(), anyInt(), any())).thenAnswer(call -> {
            java.util.Collection<Integer> who = call.getArgument(2);
            long before = stored.size();
            stored.removeIf(c -> c.getLocator().equals(call.getArgument(0)) && c.getSegmentIndex() == (int) call.getArgument(1)
                    && who.contains(c.getPassengerIndex()));
            return before - stored.size();
        });
    }

    @Test
    void should_remove_the_check_in_of_the_whole_party_on_that_flight_only() {
        deletingRemovesFromStored();
        service.checkIn("ABC234", 0);
        service.checkIn("ABC234", 1);

        boolean removed = service.checkOut("ABC234", 0);

        assertThat(removed).isTrue();
        assertThat(stored).extracting(CheckIn::getSegmentIndex).containsOnly(1);   // the other flight is still checked in
        assertThat(count("checked_out")).isEqualTo(1);
    }

    @Test
    void should_no_longer_show_boarding_passes_for_a_flight_that_was_checked_out() {
        deletingRemovesFromStored();
        service.checkIn("ABC234", 0);

        service.checkOut("ABC234", 0);

        assertThatExceptionOfType(NotCheckedInException.class).isThrownBy(() -> service.boardingPasses("ABC234", 0));
    }

    @Test
    void should_give_new_boarding_numbers_when_checking_in_again() {
        deletingRemovesFromStored();
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(2))).thenReturn(7, 20);
        assertThat(service.checkIn("ABC234", 0).passes()).extracting(BoardingPass::sequence).containsExactly(7, 8);
        service.checkOut("ABC234", 0);

        CheckInResult again = service.checkIn("ABC234", 0);

        assertThat(again.created()).isTrue();
        assertThat(again.passes()).extracting(BoardingPass::sequence).containsExactly(20, 21);   // never the numbers of the old passes
    }

    @Test
    void should_say_nothing_changed_when_the_flight_was_not_checked_in() {
        deletingRemovesFromStored();

        assertThat(service.checkOut("ABC234", 0)).isFalse();
        assertThat(count("checked_out")).isZero();
    }

    @Test
    void should_refuse_once_check_in_has_closed_and_leave_the_check_in_alone() {
        deletingRemovesFromStored();
        service.checkIn("ABC234", 0);
        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));   // 30 minutes to go: past the 60 minute cut-off

        assertThatExceptionOfType(CheckInClosedException.class).isThrownBy(() -> service.checkOut("ABC234", 0)).withMessageContaining("closed");

        assertThat(stored).hasSize(2);
        assertThat(count("refused")).isEqualTo(1);
    }

    @Test
    void should_use_the_revised_time_of_a_delayed_flight() {
        deletingRemovesFromStored();
        service.checkIn("ABC234", 0);
        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));   // on the timetable it is too late ...
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE))
                .thenReturn(Optional.of(Disruption.delayed("SK501", DATE, NOW.plus(Duration.ofHours(4)), "Late aircraft", NOW)));   // ... but it was delayed

        assertThat(service.checkOut("ABC234", 0)).isTrue();
    }

    @Test
    void should_let_a_traveller_check_out_of_a_cancelled_flight_so_the_booking_can_be_moved() {
        deletingRemovesFromStored();
        service.checkIn("ABC234", 0);
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE))
                .thenReturn(Optional.of(Disruption.cancelled("SK501", DATE, "Aircraft grounded", NOW)));

        assertThat(service.checkOut("ABC234", 0)).isTrue();   // a rebooking is refused while checked in (ADR-0022)
    }

    @Test
    void should_refuse_a_cancelled_booking_an_unknown_reference_and_a_flight_the_booking_does_not_have() {
        deletingRemovesFromStored();
        when(pnrs.findByLocator("ZZZ999")).thenReturn(Optional.empty());

        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.checkOut("ZZZ999", 0));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.checkOut("ABC234", 2));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.checkOut("ABC234", -1));
        pnr.cancel();
        assertThatExceptionOfType(BookingCancelledException.class).isThrownBy(() -> service.checkOut("ABC234", 0));
    }

    // ---- one passenger at a time (ADR-0058) ----------------------------------------------------------------------------

    @Test
    void should_check_in_only_the_passengers_asked_for_and_number_only_them() {
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(1))).thenReturn(7);

        CheckInResult result = service.checkIn("ABC234", 0, List.of(1));

        assertThat(result.created()).isTrue();
        assertThat(result.passes()).extracting(BoardingPass::passengerName).containsExactly("PERERA/RAJ");
        assertThat(result.passes()).extracting(BoardingPass::passengerIndex).containsExactly(1);
        assertThat(result.passes()).extracting(BoardingPass::sequence).containsExactly(7);
        assertThat(stored).extracting(CheckIn::getPassengerIndex).containsExactly(1);
    }

    @Test
    void should_check_in_the_rest_later_without_renumbering_anyone_and_return_everyone_who_is_in() {
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(1))).thenReturn(7, 30);
        service.checkIn("ABC234", 0, List.of(1));

        CheckInResult rest = service.checkIn("ABC234", 0, List.of());   // nobody named: everyone who is not yet in

        assertThat(rest.created()).isTrue();
        assertThat(rest.passes()).extracting(BoardingPass::passengerIndex).containsExactly(0, 1);
        assertThat(rest.passes()).extracting(BoardingPass::sequence).containsExactly(30, 7);   // Raj keeps 7
        assertThat(stored).hasSize(2);
    }

    @Test
    void should_return_the_same_passes_for_someone_already_in_even_after_the_window_closed() {
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(1))).thenReturn(7);
        service.checkIn("ABC234", 0, List.of(1));
        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));   // check-in has closed since

        CheckInResult again = service.checkIn("ABC234", 0, List.of(1));

        assertThat(again.created()).isFalse();
        assertThat(again.passes()).extracting(BoardingPass::sequence).containsExactly(7);
        verify(sequence, org.mockito.Mockito.times(1)).reserve(anyString(), any(), anyInt());
    }

    @Test
    void should_refuse_a_new_check_in_once_the_window_has_closed_even_if_someone_else_is_in() {
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(1))).thenReturn(7);
        service.checkIn("ABC234", 0, List.of(1));
        departure = Optional.of(NOW.plus(Duration.ofMinutes(30)));

        assertThatExceptionOfType(CheckInClosedException.class).isThrownBy(() -> service.checkIn("ABC234", 0, List.of(0)));

        assertThat(stored).extracting(CheckIn::getPassengerIndex).containsExactly(1);
    }

    @Test
    void should_ask_for_each_passenger_once_and_refuse_someone_who_is_not_on_the_booking() {
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(1))).thenReturn(7);

        assertThat(service.checkIn("ABC234", 0, List.of(1, 1, 1)).passes()).hasSize(1);
        for (int bad : new int[] {-1, 2, 99}) {
            assertThatExceptionOfType(InvalidBookingException.class).as("passenger " + bad)
                    .isThrownBy(() -> service.checkIn("ABC234", 0, List.of(bad))).withMessageContaining("passenger");
        }
    }

    @Test
    void should_check_out_only_the_passengers_asked_for() {
        deletingRemovesFromStored();
        service.checkIn("ABC234", 0);

        boolean removed = service.checkOut("ABC234", 0, List.of(0));

        assertThat(removed).isTrue();
        assertThat(stored).extracting(CheckIn::getPassengerIndex).containsExactly(1);
        assertThat(service.boardingPasses("ABC234", 0).passes()).extracting(BoardingPass::passengerIndex).containsExactly(1);
        assertThat(count("checked_out")).isEqualTo(1);
    }

    @Test
    void should_say_nothing_changed_when_the_passengers_asked_for_were_not_in() {
        deletingRemovesFromStored();
        when(sequence.reserve(eq("SK501"), eq(DATE), eq(1))).thenReturn(7);
        service.checkIn("ABC234", 0, List.of(1));

        assertThat(service.checkOut("ABC234", 0, List.of(0))).isFalse();
        assertThat(stored).hasSize(1);
    }

    @Test
    void should_refuse_checking_out_someone_who_is_not_on_the_booking() {
        deletingRemovesFromStored();

        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.checkOut("ABC234", 0, List.of(5)));
    }
}
