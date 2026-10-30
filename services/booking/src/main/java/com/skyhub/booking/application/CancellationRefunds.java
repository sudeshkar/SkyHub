package com.skyhub.booking.application;

import com.skyhub.booking.application.view.RefundView;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Payment;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.RefundPolicy;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PaymentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Gives money back when a paid booking is cancelled (ADR-0016). Three steps, called in this order by the cancel
 * flow: {@link #decide} (reads the timetable, so it runs before anything changes), {@link #record} (inside the
 * cancel transaction) and {@link #settle} (the provider call, outside any transaction).
 */
@Service
public class CancellationRefunds {

    private static final Logger log = LoggerFactory.getLogger(CancellationRefunds.class);

    /** A flight the timetable no longer knows cannot be about to leave: treat it as far away. */
    private static final Duration UNKNOWN_FLIGHT_DISTANCE = Duration.ofDays(365);

    private final PaymentRepository payments;
    private final DisruptionRepository disruptions;
    private final FlightCatalog flights;
    private final RefundPolicy policy;
    private final PaymentGateway gateway;
    private final PaymentMetrics metrics;
    private final Clock clock;
    private final TransactionOperations transactions;

    public CancellationRefunds(PaymentRepository payments, DisruptionRepository disruptions, FlightCatalog flights,
                               RefundPolicy policy, PaymentGateway gateway, PaymentMetrics metrics, Clock clock,
                               TransactionOperations transactions) {
        this.payments = payments;
        this.disruptions = disruptions;
        this.flights = flights;
        this.policy = policy;
        this.gateway = gateway;
        this.metrics = metrics;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * @throws FlightCatalogUnavailableException when the timetable cannot be checked; nothing has been changed
     */
    public RefundPolicy.Decision decide(Pnr pnr) {
        Instant now = clock.instant();
        Instant first = null;
        for (PnrSegment segment : pnr.getSegments()) {
            Disruption disruption = disruptions
                    .findByFlightNumberAndDepartureDate(segment.flightNumber(), segment.departureDate()).orElse(null);
            if (disruption != null && disruption.isCancelled()) {
                return policy.decide(now, true, now);
            }
            Instant departure = disruption != null
                    ? disruption.getRevisedDeparture()
                    : flights.departure(segment.flightNumber(), segment.departureDate()).orElse(null);
            if (departure != null && (first == null || departure.isBefore(first))) {
                first = departure;
            }
        }
        return policy.decide(first == null ? now.plus(UNKNOWN_FLIGHT_DISTANCE) : first, false, now);
    }

    /** Runs inside the cancel transaction. A booking with no payment (made before payments existed) is left alone. */
    public void record(String locator, RefundPolicy.Decision decision) {
        Payment payment = payments.findByLocator(locator).orElse(null);
        if (payment == null) {
            return;
        }
        if (decision == RefundPolicy.Decision.FULL_REFUND) {
            payment.refundDue();
        } else {
            payment.notRefundable();
            metrics.record(PaymentMetrics.Outcome.CANCEL_NOT_REFUNDABLE);
        }
        payments.save(payment);
    }

    /**
     * Pays any refund that is due, then reports where the money stands. Never throws on a provider failure: the
     * booking is already cancelled, and the refund stays {@code PENDING} so cancelling again retries it.
     */
    public Optional<RefundView> settle(String locator) {
        Payment payment = payments.findByLocator(locator).orElse(null);
        if (payment != null && payment.getStatus() == Payment.Status.REFUND_DUE) {
            payOut(payment);
        }
        return Optional.ofNullable(payment).flatMap(CancellationRefunds::view);
    }

    /** Where the money stands, read only: never calls the provider, so a plain page view cannot move money. */
    public Optional<RefundView> status(String locator) {
        return payments.findByLocator(locator).flatMap(CancellationRefunds::view);
    }

    private void payOut(Payment payment) {
        try {
            gateway.refund(payment.getChargeId());
        } catch (RuntimeException failed) {
            metrics.record(PaymentMetrics.Outcome.CANCEL_REFUND_FAILED);
            // Cancelled, money owed, provider did not confirm: a person looks if it stays that way (runbook).
            log.atError().addKeyValue("payment.charge", payment.getChargeId()).addKeyValue("pnr.locator", payment.getLocator())
                    .setCause(failed).log("Refund after cancellation failed; it stays pending");
            return;
        }
        transactions.executeWithoutResult(status -> {
            payment.refunded();
            payments.save(payment);
        });
        metrics.record(PaymentMetrics.Outcome.CANCEL_REFUNDED);
        log.atInfo().addKeyValue("payment.charge", payment.getChargeId()).addKeyValue("pnr.locator", payment.getLocator())
                .log("Refunded after cancellation");
    }

    private static Optional<RefundView> view(Payment payment) {
        RefundView.Status status = switch (payment.getStatus()) {
            case CAPTURED -> null;
            case REFUND_DUE -> RefundView.Status.PENDING;
            case REFUNDED -> RefundView.Status.REFUNDED;
            case NO_REFUND -> RefundView.Status.NOT_REFUNDABLE;
        };
        return Optional.ofNullable(status)
                .map(s -> new RefundView(s, payment.getAmount().amount(), payment.getAmount().currency()));
    }
}
