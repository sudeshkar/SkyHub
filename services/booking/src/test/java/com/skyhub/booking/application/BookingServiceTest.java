package com.skyhub.booking.application;

import com.skyhub.booking.TestCommands;
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
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Payment;
import com.skyhub.booking.domain.PaymentDeclinedException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PriceChangedException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatSpec;
import com.skyhub.booking.domain.SeatStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.PaymentRepository;
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

    private final SimpleMeterRegistry holdMetrics = new SimpleMeterRegistry();
    private final SimpleMeterRegistry paymentRegistry = new SimpleMeterRegistry();
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final BookingEvents events = mock(BookingEvents.class);
    /** Every flight costs 100.00 USD a passenger, so SK501 + SK901 for one passenger is 200.00. */
    private final PricingService pricing = new PricingService(
            (flight, date) -> Optional.of(new FlightInfo(NOW.plusSeconds(18000), Money.of("100.00", "USD"))),
            mock(FlightSalesGuard.class));

    private final BookingService service = new BookingService(seats, pnrs, initializer, new SeatMapCache(new SimpleMeterRegistry()), new CreatePnrValidator(List.of(new SegmentCountRule(), new PassengerCountRule(),
                    new DistinctFlightsRule(), new OneSeatPerFlightRule(), new NoSharedSeatsRule())), () -> "ABC234",
            Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction(),
            new BookingMetrics(new SimpleMeterRegistry()), new HoldMetrics(holdMetrics),
            pricing, gateway, payments, new PaymentMetrics(paymentRegistry), events);

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
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        when(gateway.charge(eq("tok_visa"), any(Money.class), anyString()))
                .thenAnswer(call -> new PaymentGateway.Charge("ch_1", call.getArgument(1)));
    }

    private double payment(String outcome) {
        return paymentRegistry.counter("skyhub.payments", "outcome", outcome).count();
    }

    private CreatePnrCommand paidWith(String token, java.math.BigDecimal expected) {
        return new CreatePnrCommand("priya@example.com", List.of(SK501, SK901),
                List.of(passenger("Priya", "Perera", "11D", "24A")), null, token, expected);
    }

    @Test
    void should_charge_the_price_the_server_worked_out_before_booking_and_record_the_payment() {
        service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY);

        var order = inOrder(gateway, pnrs, payments);
        order.verify(gateway).charge("tok_visa", Money.of("200.00", "USD"), "booking-" + Sha256.hex(KEY));
        order.verify(pnrs).saveAndFlush(any(Pnr.class));
        var saved = org.mockito.ArgumentCaptor.forClass(Payment.class);
        order.verify(payments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLocator()).isEqualTo("ABC234");
        assertThat(saved.getValue().getChargeId()).isEqualTo("ch_1");
        assertThat(saved.getValue().getAmount()).isEqualTo(Money.of("200.00", "USD"));
        assertThat(payment("captured")).isEqualTo(1);
    }

    @Test
    void should_charge_for_every_passenger() {
        service.createPnr(command(passenger("Priya", "Perera", "11D", "24A"), passenger("Raj", "Perera", "11E", "24B")), KEY);

        verify(gateway).charge(eq("tok_visa"), eq(Money.of("400.00", "USD")), anyString());
    }

    @Test
    void should_book_nothing_when_the_payment_is_declined() {
        when(gateway.charge(eq("tok_declined"), any(Money.class), anyString()))
                .thenThrow(new PaymentDeclinedException("Your card was declined"));

        assertThatExceptionOfType(PaymentDeclinedException.class)
                .isThrownBy(() -> service.createPnr(paidWith("tok_declined", null), KEY));

        assertThat(inventory.get("SK50111D").getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        verify(pnrs, never()).saveAndFlush(any());
        verify(gateway, never()).refund(anyString());
        assertThat(payment("declined")).isEqualTo(1);
    }

    @Test
    void should_book_nothing_when_the_provider_is_down() {
        when(gateway.charge(eq("tok_provider_down"), any(Money.class), anyString()))
                .thenThrow(new PaymentUnavailableException("no answer", null));

        assertThatExceptionOfType(PaymentUnavailableException.class)
                .isThrownBy(() -> service.createPnr(paidWith("tok_provider_down", null), KEY));

        verify(pnrs, never()).saveAndFlush(any());
        assertThat(payment("unavailable")).isEqualTo(1);
    }

    @Test
    void should_refuse_without_charging_when_the_price_the_traveller_saw_is_not_the_price() {
        assertThatExceptionOfType(PriceChangedException.class)
                .isThrownBy(() -> service.createPnr(paidWith("tok_visa", new java.math.BigDecimal("150.00")), KEY))
                .withMessageContaining("200.00");

        verify(gateway, never()).charge(anyString(), any(Money.class), anyString());
        assertThat(payment("price_changed")).isEqualTo(1);
    }

    @Test
    void should_accept_the_expected_price_however_many_decimals_it_is_written_with() {
        assertThat(service.createPnr(paidWith("tok_visa", new java.math.BigDecimal("200")), KEY).created()).isTrue();
    }

    @Test
    void should_refund_the_charge_when_the_seat_is_gone_after_paying() {
        inventory.get("SK90124A").book("OTHER1");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY));

        verify(gateway).refund("ch_1");
        verify(payments, never()).saveAndFlush(any());
        assertThat(payment("refunded")).isEqualTo(1);
    }

    @Test
    void should_still_report_the_real_failure_and_raise_the_alarm_when_the_refund_fails() {
        inventory.get("SK90124A").book("OTHER1");
        org.mockito.Mockito.doThrow(new PaymentUnavailableException("no answer", null)).when(gateway).refund("ch_1");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY));

        assertThat(payment("refund_failed")).isEqualTo(1);
        assertThat(payment("refunded")).isZero();
    }

    @Test
    void should_not_charge_again_for_a_replayed_request() {
        Pnr existing = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(SK501, SK901),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A"))), NOW, Sha256.hex(KEY),
                RequestFingerprint.of(command(passenger("Priya", "Perera", "11D", "24A"))));
        when(pnrs.findByIdempotencyKeyHash(Sha256.hex(KEY))).thenReturn(Optional.of(existing));

        var result = service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY);

        assertThat(result.created()).isFalse();
        verifyNoInteractions(gateway);
    }

    @Test
    void should_record_a_booking_confirmed_event_with_the_total_charged() {
        service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY);

        var total = org.mockito.ArgumentCaptor.forClass(Money.class);
        verify(events).bookingConfirmed(any(Pnr.class), total.capture());
        assertThat(total.getValue()).isEqualTo(Money.of("200.00", "USD"));
    }

    @Test
    void should_record_no_event_for_a_replay_a_decline_or_a_taken_seat() {
        var existing = Pnr.confirmed("XYZ789", new ContactEmail("priya@example.com"), List.of(SK501, SK901),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A"))), NOW, KEY,
                RequestFingerprint.of(command(passenger("Priya", "Perera", "11D", "24A"))));
        when(pnrs.findByIdempotencyKeyHash(Sha256.hex(KEY))).thenReturn(Optional.of(existing));
        service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY);

        verifyNoInteractions(events);
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
                .isThrownBy(() -> service.createPnr(TestCommands.pnr("priya@example.com", List.of(SK501, SK901), ten), KEY));
    }

    @Test
    void should_reject_the_same_flight_twice() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createPnr(TestCommands.pnr("priya@example.com", List.of(SK501, SK501),
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
    void should_turn_held_seats_into_a_booking_when_the_hold_is_valid() {
        hold("hold-1", NOW.plusSeconds(60), "SK50111D", "SK90124A");

        service.createPnr(heldCommand("hold-1", passenger("Priya", "Perera", "11D", "24A")), KEY);

        assertThat(inventory.get("SK50111D").getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(inventory.get("SK50111D").getHoldId()).isNull();
        assertThat(inventory.get("SK90124A").getPnrLocator()).isEqualTo("ABC234");
        assertThat(holdMetrics.counter("skyhub.seat.holds", "outcome", "converted").count()).isEqualTo(1);
    }

    @Test
    void should_refuse_a_booking_whose_hold_has_expired_even_if_the_cleanup_has_not_run() {
        hold("hold-1", NOW.minusSeconds(1), "SK50111D", "SK90124A");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createPnr(heldCommand("hold-1", passenger("Priya", "Perera", "11D", "24A")), KEY))
                .withMessageContaining("expired");
        verify(pnrs, never()).saveAndFlush(any());
    }

    @Test
    void should_refuse_a_booking_that_uses_someone_elses_hold() {
        hold("hold-1", NOW.plusSeconds(60), "SK50111D", "SK90124A");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createPnr(heldCommand("hold-2", passenger("Priya", "Perera", "11D", "24A")), KEY));
    }

    @Test
    void should_refuse_a_direct_booking_of_a_seat_someone_else_is_holding() {
        hold("hold-1", NOW.plusSeconds(60), "SK50111D");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createPnr(command(passenger("Priya", "Perera", "11D", "24A")), KEY));
    }

    private void hold(String holdId, Instant until, String... keys) {
        for (String key : keys) {
            inventory.get(key).hold(holdId, until);
        }
    }

    private static CreatePnrCommand heldCommand(String holdId, PassengerCommand... passengers) {
        return TestCommands.pnr("priya@example.com", List.of(SK501, SK901), List.of(passengers), holdId);
    }

    private static CreatePnrCommand command(PassengerCommand... passengers) {
        return TestCommands.pnr("priya@example.com", List.of(SK501, SK901), List.of(passengers));
    }

    private static PassengerCommand passenger(String first, String last, String... seats) {
        return new PassengerCommand(first, last, List.of(seats));
    }
}
