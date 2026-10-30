package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.view.RefundView;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Payment;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.RefundPolicy;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class CancellationRefundsTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final FlightCatalog flights = mock(FlightCatalog.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private Pnr pnr;
    private Payment payment;
    private CancellationRefunds refunds;

    @BeforeEach
    void setUp() {
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", DATE), new PnrSegment("SK901", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A"))),
                NOW.minus(Duration.ofDays(1)), "key-hash", "fingerprint");
        payment = Payment.captured("ABC234", "ch_1", Money.of("200.00", "USD"), NOW.minus(Duration.ofDays(1)));
        when(payments.findByLocator("ABC234")).thenReturn(Optional.of(payment));
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any())).thenReturn(Optional.empty());
        refunds = new CancellationRefunds(payments, disruptions, flights, new RefundPolicy(), gateway,
                new PaymentMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC), TransactionOperations.withoutTransaction());
    }

    private void departures(Instant first, Instant second) {
        when(flights.departure("SK501", DATE)).thenReturn(Optional.of(first));
        when(flights.departure("SK901", DATE)).thenReturn(Optional.of(second));
    }

    private double counted(String outcome) {
        return registry.get("skyhub.payments").tag("outcome", outcome).counter().count();
    }

    // ---- decide ----

    @Test
    void should_refund_in_full_when_the_first_flight_is_days_away() {
        departures(NOW.plus(Duration.ofDays(4)), NOW.plus(Duration.ofDays(4)).plusSeconds(7200));

        assertThat(refunds.decide(pnr)).isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_judge_by_the_earliest_flight_not_the_last() {
        departures(NOW.plus(Duration.ofHours(3)), NOW.plus(Duration.ofDays(4)));

        assertThat(refunds.decide(pnr)).isEqualTo(RefundPolicy.Decision.NO_REFUND);
    }

    @Test
    void should_refund_in_full_when_the_airline_cancelled_any_flight_even_at_the_last_minute() {
        when(disruptions.findByFlightNumberAndDepartureDate("SK901", DATE))
                .thenReturn(Optional.of(Disruption.cancelled("SK901", DATE, "Crew shortage", NOW)));
        departures(NOW.plus(Duration.ofMinutes(30)), NOW.plus(Duration.ofHours(3)));

        assertThat(refunds.decide(pnr)).isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_use_the_revised_departure_of_a_delayed_flight() {
        // Timetable says 3 hours away (no refund), but a delay pushed it to 2 days away (refund).
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(Optional.of(
                Disruption.delayed("SK501", DATE, NOW.plus(Duration.ofDays(2)), "Weather", NOW)));
        departures(NOW.plus(Duration.ofHours(3)), NOW.plus(Duration.ofDays(4)));

        assertThat(refunds.decide(pnr)).isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_treat_a_flight_the_timetable_no_longer_knows_as_far_away() {
        when(flights.departure("SK501", DATE)).thenReturn(Optional.empty());
        when(flights.departure("SK901", DATE)).thenReturn(Optional.empty());

        assertThat(refunds.decide(pnr)).isEqualTo(RefundPolicy.Decision.FULL_REFUND);
    }

    @Test
    void should_not_decide_anything_when_the_timetable_cannot_be_checked() {
        when(flights.departure("SK501", DATE)).thenThrow(new FlightCatalogUnavailableException("down", null));

        assertThatExceptionOfType(FlightCatalogUnavailableException.class).isThrownBy(() -> refunds.decide(pnr));
    }

    // ---- record ----

    @Test
    void should_mark_the_payment_refund_due_when_the_rules_say_refund() {
        assertThat(refunds.record("ABC234", RefundPolicy.Decision.FULL_REFUND)).hasValueSatisfying(
                paid -> assertThat(paid.amount()).isEqualByComparingTo("200.00"));

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUND_DUE);
        verify(payments).save(payment);
    }

    @Test
    void should_mark_the_payment_not_refundable_and_count_it_when_cancelled_too_late() {
        refunds.record("ABC234", RefundPolicy.Decision.NO_REFUND);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.NO_REFUND);
        assertThat(counted("cancel_not_refundable")).isEqualTo(1.0);
    }

    @Test
    void should_do_nothing_for_a_booking_that_was_never_paid() {
        when(payments.findByLocator("OLD123")).thenReturn(Optional.empty());

        assertThat(refunds.record("OLD123", RefundPolicy.Decision.FULL_REFUND)).isEmpty();

        verify(payments, never()).save(any());
    }

    // ---- settle ----

    @Test
    void should_give_the_money_back_and_say_so() {
        payment.refundDue();

        Optional<RefundView> view = refunds.settle("ABC234");

        verify(gateway).refund("ch_1");
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(view).contains(new RefundView(RefundView.Status.REFUNDED, new BigDecimal("200.00"), "USD"));
        assertThat(counted("cancel_refunded")).isEqualTo(1.0);
    }

    @Test
    void should_keep_the_refund_pending_and_count_the_failure_when_the_provider_is_down() {
        payment.refundDue();
        doThrow(new PaymentUnavailableException("provider down", null)).when(gateway).refund("ch_1");

        Optional<RefundView> view = refunds.settle("ABC234");

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUND_DUE);
        assertThat(view).contains(new RefundView(RefundView.Status.PENDING, new BigDecimal("200.00"), "USD"));
        assertThat(counted("cancel_refund_failed")).isEqualTo(1.0);
    }

    @Test
    void should_not_call_the_provider_for_a_refund_that_was_already_given() {
        payment.refundDue();
        payment.refunded();

        Optional<RefundView> view = refunds.settle("ABC234");

        verify(gateway, never()).refund(anyString());
        assertThat(view).contains(new RefundView(RefundView.Status.REFUNDED, new BigDecimal("200.00"), "USD"));
    }

    @Test
    void should_not_call_the_provider_when_nothing_is_owed() {
        payment.notRefundable();

        Optional<RefundView> view = refunds.settle("ABC234");

        verify(gateway, never()).refund(anyString());
        assertThat(view).contains(new RefundView(RefundView.Status.NOT_REFUNDABLE, new BigDecimal("200.00"), "USD"));
    }

    @Test
    void should_show_no_refund_for_a_booking_that_is_not_cancelled_yet() {
        assertThat(refunds.settle("ABC234")).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void should_show_no_refund_for_a_booking_without_a_payment() {
        when(payments.findByLocator("OLD123")).thenReturn(Optional.empty());

        assertThat(refunds.settle("OLD123")).isEmpty();
    }

    // ---- status (read only) ----

    @Test
    void should_report_a_pending_refund_without_calling_the_provider() {
        payment.refundDue();

        Optional<RefundView> view = refunds.status("ABC234");

        verifyNoInteractions(gateway);
        assertThat(view).contains(new RefundView(RefundView.Status.PENDING, new BigDecimal("200.00"), "USD"));
    }
}
