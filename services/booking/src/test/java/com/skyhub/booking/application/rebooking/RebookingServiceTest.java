package com.skyhub.booking.application.rebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.BookingEvents;
import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.application.SeatInventoryInitializer;
import com.skyhub.booking.application.SeatMapCache;
import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.Cabin;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PartyTogetherAllocator;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.Rebooking;
import com.skyhub.booking.domain.SeatAllocator;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatSpec;
import com.skyhub.booking.domain.SeatStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.RebookingRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class RebookingServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant DEPARTS = Instant.parse("2026-11-02T08:30:00Z");
    private static final PnrSegment FROM = new PnrSegment("SK501", DAY);
    private static final PnrSegment TO = new PnrSegment("SK501", DAY.plusDays(1));

    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final SeatInventoryRepository seats = mock(SeatInventoryRepository.class);
    private final CheckInRepository checkIns = mock(CheckInRepository.class);
    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final com.skyhub.booking.application.FlightCatalog catalog = mock(com.skyhub.booking.application.FlightCatalog.class);
    private final SeatInventoryInitializer initializer = mock(SeatInventoryInitializer.class);
    private final RebookingRepository audit = mock(RebookingRepository.class);
    private final BookingEvents events = mock(BookingEvents.class);
    private final SeatMapCache seatMaps = new SeatMapCache(new SimpleMeterRegistry());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final Map<String, SeatInventory> inventory = new HashMap<>();
    private Pnr pnr;
    private RebookingService service;

    private static FlightInfo flight(Instant departure) {
        return new FlightInfo(departure, Money.of("100.00", "USD"), "DXB", "LHR");
    }

    private static String key(PnrSegment flight, String seat) {
        return flight.flightNumber() + flight.departureDate() + seat;
    }

    private void addSeat(PnrSegment flight, int row, String letter, Cabin cabin) {
        inventory.put(key(flight, row + letter), SeatInventory.available(flight.flightNumber(), flight.departureDate(), new SeatSpec(row, letter, cabin)));
    }

    @BeforeEach
    void setUp() {
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(FROM),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D")), new PnrPassenger("Anil", "Perera", List.of("11E"))),
                NOW, "key", "fingerprint");
        addSeat(FROM, 11, "D", Cabin.ECONOMY);
        addSeat(FROM, 11, "E", Cabin.ECONOMY);
        inventory.get(key(FROM, "11D")).book("ABC234");
        inventory.get(key(FROM, "11E")).book("ABC234");
        for (String letter : List.of("A", "B", "C", "D")) {
            addSeat(TO, 20, letter, Cabin.ECONOMY);
        }
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        when(pnrs.saveAndFlush(any(Pnr.class))).thenAnswer(call -> call.getArgument(0));
        when(catalog.find("SK501", DAY)).thenReturn(Optional.of(flight(DEPARTS)));
        when(catalog.find("SK501", DAY.plusDays(1))).thenReturn(Optional.of(flight(DEPARTS.plusSeconds(86_400))));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY))
                .thenReturn(Optional.of(Disruption.cancelled("SK501", DAY, "Aircraft grounded", NOW)));
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY.plusDays(1))).thenReturn(Optional.empty());
        when(seats.findByFlightNumberAndDepartureDateAndSeatNumber(anyString(), any(LocalDate.class), anyString())).thenAnswer(call ->
                Optional.ofNullable(inventory.get(call.<String>getArgument(0) + call.<LocalDate>getArgument(1) + call.<String>getArgument(2))));
        when(seats.findByFlightNumberAndDepartureDateOrderByRowNumberAscLetterAsc(anyString(), any(LocalDate.class))).thenAnswer(call ->
                new ArrayList<>(inventory.values().stream()
                        .filter(s -> s.getFlightNumber().equals(call.getArgument(0)) && s.getDepartureDate().equals(call.getArgument(1)))
                        .sorted(java.util.Comparator.comparingInt(SeatInventory::getRowNumber).thenComparing(SeatInventory::getLetter)).toList()));
        service = service(new PartyTogetherAllocator(), RebookingRules.defaults());
    }

    private RebookingService service(SeatAllocator allocator, List<RebookingRule> rules) {
        return new RebookingService(pnrs, seats, checkIns, disruptions, catalog, initializer, allocator, rules, audit, seatMaps, events,
                new RebookingMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction());
    }

    private RebookCommand command() {
        return new RebookCommand("ABC234", 0, "SK501", DAY.plusDays(1), "priya.ops");
    }

    private double count(String outcome) {
        return registry.get("skyhub.rebookings").tag("outcome", outcome).counter().count();
    }

    @Test
    void should_move_the_booking_book_the_new_seats_together_and_free_the_old_ones() {
        RebookingResult result = service.rebook(command());

        assertThat(result.changed()).isTrue();
        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.seats()).containsExactly("20A", "20B");
        assertThat(pnr.getSegments()).containsExactly(TO);
        assertThat(inventory.get(key(TO, "20A")).getPnrLocator()).isEqualTo("ABC234");
        assertThat(inventory.get(key(TO, "20B")).getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(inventory.get(key(FROM, "11D")).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.get(key(FROM, "11E")).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(count("rebooked")).isEqualTo(1);
    }

    @Test
    void should_record_who_moved_whom_and_tell_the_traveller_in_the_same_transaction() {
        service.rebook(command());

        ArgumentCaptor<Rebooking> saved = ArgumentCaptor.forClass(Rebooking.class);
        verify(audit).save(saved.capture());
        assertThat(saved.getValue().getLocator()).isEqualTo("ABC234");
        assertThat(saved.getValue().getRebookedBy()).isEqualTo("priya.ops");
        assertThat(saved.getValue().getFromFlight()).isEqualTo("SK501");
        assertThat(saved.getValue().getToDate()).isEqualTo(DAY.plusDays(1));
        assertThat(saved.getValue().getRebookedAt()).isEqualTo(NOW);
        verify(events).bookingRebooked(pnr, FROM, TO);
    }

    @Test
    void should_prepare_the_new_flights_seats_before_anything_is_booked() {
        service.rebook(command());

        verify(initializer).ensureInventory("SK501", DAY.plusDays(1));
    }

    @Test
    void should_choose_seats_separately_for_each_cabin_the_party_was_sitting_in() {
        addSeat(FROM, 2, "A", Cabin.BUSINESS);
        inventory.get(key(FROM, "2A")).book("ABC234");
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(FROM),
                List.of(new PnrPassenger("Priya", "Perera", List.of("2A")), new PnrPassenger("Anil", "Perera", List.of("11E"))),
                NOW, "key", "fingerprint");
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        addSeat(TO, 3, "A", Cabin.BUSINESS);
        SeatAllocator spy = mock(SeatAllocator.class);
        when(spy.allocate(any(), eq(1))).thenAnswer(call -> List.of(call.<List<SeatInventory>>getArgument(0).get(0)));

        RebookingResult result = service(spy, RebookingRules.defaults()).rebook(command());

        verify(spy, times(2)).allocate(any(), eq(1));          // once for business, once for economy
        assertThat(result.seats().get(0)).isEqualTo("3A");      // the business passenger stays in business
        assertThat(result.seats().get(1)).isEqualTo("20A");
    }

    @Test
    void should_change_nothing_and_say_so_when_the_booking_is_already_on_the_new_flight() {
        service.rebook(command());

        RebookingResult again = service.rebook(command());

        assertThat(again.changed()).isFalse();
        verify(audit, times(1)).save(any());
        verify(events, times(1)).bookingRebooked(any(), any(), any());
        assertThat(count("unchanged")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_move_that_a_rule_forbids_and_touch_nothing() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DAY)).thenReturn(Optional.empty());

        assertThatExceptionOfType(RebookingRefusedException.class).isThrownBy(() -> service.rebook(command()))
                .withMessageContaining("no recorded");

        assertThat(inventory.get(key(FROM, "11D")).getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(inventory.get(key(TO, "20A")).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verifyNoInteractions(events, audit);
        assertThat(count("refused")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_flight_that_does_not_operate() {
        when(catalog.find("SK501", DAY.plusDays(1))).thenReturn(Optional.empty());

        assertThatExceptionOfType(RebookingRefusedException.class).isThrownBy(() -> service.rebook(command()))
                .withMessageContaining("does not operate");
    }

    @Test
    void should_book_no_seat_at_all_when_the_party_does_not_fit() {
        inventory.get(key(TO, "20C")).book("OTHER1");
        inventory.get(key(TO, "20D")).book("OTHER2");
        inventory.get(key(TO, "20B")).book("OTHER3");           // only 20A is free for two passengers

        assertThatExceptionOfType(SeatUnavailableException.class).isThrownBy(() -> service.rebook(command()));

        assertThat(inventory.get(key(TO, "20A")).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.get(key(FROM, "11D")).getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(pnr.getSegments()).containsExactly(FROM);
        verify(events, never()).bookingRebooked(any(), any(), any());
    }

    @Test
    void should_refuse_an_unknown_or_cancelled_booking() {
        when(pnrs.findByLocator("ZZZ999")).thenReturn(Optional.empty());
        assertThatExceptionOfType(PnrNotFoundException.class)
                .isThrownBy(() -> service.rebook(new RebookCommand("ZZZ999", 0, "SK501", DAY.plusDays(1), "priya.ops")));

        pnr.cancel();
        assertThatExceptionOfType(BookingCancelledException.class).isThrownBy(() -> service.rebook(command()));
    }

    @Test
    void should_use_whatever_rules_it_is_given_so_a_new_rule_needs_no_change_to_the_service() {
        RebookingRule forbidEverything = context -> Optional.of("not today");

        assertThatExceptionOfType(RebookingRefusedException.class)
                .isThrownBy(() -> service(new PartyTogetherAllocator(), List.of(forbidEverything)).rebook(command()))
                .withMessage("not today");
    }

    @Test
    void should_forget_the_cached_seat_maps_of_both_flights() {
        seatMaps.get("SK501", DAY, () -> List.of());
        seatMaps.get("SK501", DAY.plusDays(1), () -> List.of());
        int[] reloads = {0};

        service.rebook(command());
        seatMaps.get("SK501", DAY, () -> { reloads[0]++; return List.of(); });
        seatMaps.get("SK501", DAY.plusDays(1), () -> { reloads[0]++; return List.of(); });

        assertThat(reloads[0]).isEqualTo(2);
    }
}
