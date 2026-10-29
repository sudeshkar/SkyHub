package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.application.rules.DistinctFlightsRule;
import com.skyhub.booking.application.rules.NoSharedSeatsRule;
import com.skyhub.booking.application.rules.OneSeatPerFlightRule;
import com.skyhub.booking.application.rules.PassengerCountRule;
import com.skyhub.booking.application.rules.SegmentCountRule;
import com.skyhub.booking.domain.Cabin;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.IdempotencyKeyReusedException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatSpec;
import com.skyhub.booking.domain.SeatStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class BookingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final PnrSegment SK501 = new PnrSegment("SK501", DATE);
    private static final PnrSegment SK901 = new PnrSegment("SK901", DATE);
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final String KEY = "key-12345678";

    private final SeatInventoryRepository seats = mock(SeatInventoryRepository.class);
    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final SeatInventoryInitializer initializer = mock(SeatInventoryInitializer.class);
    private final Map<String, SeatInventory> inventory = new HashMap<>();

    private final BookingService service = new BookingService(seats, pnrs, initializer, new SeatMapCache(new SimpleMeterRegistry()), new CreatePnrValidator(List.of(new SegmentCountRule(), new PassengerCountRule(),
                    new DistinctFlightsRule(), new OneSeatPerFlightRule(), new NoSharedSeatsRule())), () -> "ABC234",
            Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction(),
            new BookingMetrics(new SimpleMeterRegistry()));

    @BeforeEach
    void setUp() {
        for (PnrSegment segment : List.of(SK501, SK901)) {
            for (String seat : List.of("11D", "11E", "24A", "24B")) {
                inventory.put(segment.flightNumber() + seat, SeatInventory.available(segment.flightNumber(), DATE,
                        new SeatSpec(Integer.parseInt(seat.substring(0, 2)), seat.substring(2), Cabin.ECONOMY)));
            }
        }
        when(seats.findByFlightNumberAndDepartureDateAndSeatNumber(anyString(), eq(DATE), anyString()))
                .thenAnswer(call -> Optional.ofNullable(
                        inventory.get(call.<String>getArgument(0) + call.<String>getArgument(2))));
        when(pnrs.findByIdempotencyKeyHash(anyString())).thenReturn(Optional.empty());
        when(pnrs.saveAndFlush(any(Pnr.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void should_create_pnr_with_generated_locator_and_current_time() {
        var result = service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY);

        assertThat(result.created()).isTrue();
        assertThat(result.pnr().locator()).isEqualTo("ABC234");
        assertThat(result.pnr().createdAt()).isEqualTo(NOW);
        assertThat(result.pnr().segments()).extracting(PnrView.SegmentView::flightNumber)
                .containsExactly("SK501", "SK901");
        assertThat(result.pnr().passengers().getFirst().seats()).containsExactly("11D", "24A");
        assertThat(result.pnr().maskedContactEmail()).isEqualTo("p****@example.com");
    }

    @Test
    void should_book_one_seat_per_passenger_per_segment() {
        service.createPnr(command(passenger("Priya", "Perera", "11D", "24A"), passenger("Raj", "Perera", "11E", "24B")), KEY);

        assertThat(List.of("SK50111D", "SK50111E", "SK90124A", "SK90124B"))
                .allSatisfy(seat -> {
                    assertThat(inventory.get(seat).getStatus()).isEqualTo(SeatStatus.BOOKED);
                    assertThat(inventory.get(seat).getPnrLocator()).isEqualTo("ABC234");
                });
    }

    @Test
    void should_prepare_inventory_for_every_segment_before_booking() {
        service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY);

        var order = inOrder(initializer, pnrs);
        order.verify(initializer).ensureInventory("SK501", DATE);
        order.verify(initializer).ensureInventory("SK901", DATE);
        order.verify(pnrs).saveAndFlush(any());
    }

    @Test
    void should_return_existing_pnr_when_idempotency_key_is_replayed() {
        var command = command(passenger("Priya", "Perera", "11D", "24A"));
        var existing = Pnr.confirmed("XYZ789", new ContactEmail("priya@example.com"), List.of(SK501, SK901),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A"))), NOW, KEY,
                RequestFingerprint.of(command));
        when(pnrs.findByIdempotencyKeyHash(Sha256.hex(KEY))).thenReturn(Optional.of(existing));

        var result = service.createPnr(command, KEY);

        assertThat(result.created()).isFalse();
        assertThat(result.pnr().locator()).isEqualTo("XYZ789");
        verifyNoInteractions(initializer);
        verify(pnrs, never()).saveAndFlush(any());
    }

    @Test
    void should_reject_idempotency_key_reused_for_a_different_request() {
        var existing = Pnr.confirmed("XYZ789", new ContactEmail("someone@example.com"), List.of(SK501),
                List.of(new PnrPassenger("Ann", "Silva", List.of("11D"))), NOW, KEY, "other-request");
        when(pnrs.findByIdempotencyKeyHash(Sha256.hex(KEY))).thenReturn(Optional.of(existing));

        assertThatExceptionOfType(IdempotencyKeyReusedException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY));
        verifyNoInteractions(initializer);
    }

    @Test
    void should_reject_passenger_without_one_seat_per_segment() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "11D")), KEY))
                .withMessageContaining("one seat per flight");
    }

    @Test
    void should_reject_same_seat_twice_on_one_segment() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createPnr(command(
                        passenger("Priya", "Perera", "11D", "24A"), passenger("Raj", "Perera", "11D", "24B")), KEY))
                .withMessageContaining("11D");
    }

    @Test
    void should_reject_more_than_nine_passengers() {
        var ten = Collections.nCopies(10, passenger("Priya", "Perera", "11D", "24A"));

        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createPnr(new CreatePnrCommand("priya@example.com", List.of(SK501, SK901), ten), KEY));
    }

    @Test
    void should_reject_the_same_flight_twice() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createPnr(new CreatePnrCommand("priya@example.com", List.of(SK501, SK501),
                        List.of(passenger("Priya", "Perera", "11D", "24A"))), KEY));
    }

    @Test
    void should_reject_unknown_seat() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "99K", "24A")), KEY))
                .withMessageContaining("99K");
    }

    @Test
    void should_refuse_seat_that_is_already_booked_and_save_nothing() {
        inventory.get("SK90124A").book("OTHER1");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY));
        verify(pnrs, never()).saveAndFlush(any());
    }

    @Test
    void should_find_pnr_by_locator_and_last_name() {
        var pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(SK501),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D"))), NOW, KEY, "fingerprint");
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));

        assertThat(service.findPnr("ABC234", "perera").locator()).isEqualTo("ABC234");
        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.findPnr("ABC234", "Silva"));
        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.findPnr("ZZZZZZ", "Perera"));
    }

    private static CreatePnrCommand command(PassengerCommand... passengers) {
        return new CreatePnrCommand("priya@example.com", List.of(SK501, SK901), List.of(passengers));
    }

    private static PassengerCommand passenger(String first, String last, String... seats) {
        return new PassengerCommand(first, last, List.of(seats));
    }
}
