package com.skyhub.booking.application;

import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.IdempotencyKeyReusedException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.LocatorGenerator;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Payment;
import com.skyhub.booking.domain.PaymentDeclinedException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PriceChangedException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.persistence.PaymentRepository;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import com.skyhub.booking.application.BookingMetrics.Outcome;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.SeatUnavailableException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Create-PNR workflow (docs/design/02-lld.md §4.5). Returns read models only; entities stay inside.
 * Every attempt records one outcome metric; business events are logged without personal data.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final SeatInventoryRepository seats;
    private final PnrRepository pnrs;
    private final SeatInventoryInitializer inventory;
    private final SeatMapCache seatMaps;
    private final CreatePnrValidator validator;
    private final LocatorGenerator locators;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final BookingMetrics metrics;
    private final HoldMetrics holdMetrics;
    private final PricingService pricing;
    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final PaymentMetrics paymentMetrics;
    private final BookingEvents events;
    private final UnrefundedCharges unrefunded;
    private final EmailIndex emailIndex;

    public BookingService(SeatInventoryRepository seats, PnrRepository pnrs, SeatInventoryInitializer inventory,
                          SeatMapCache seatMaps, CreatePnrValidator validator, LocatorGenerator locators, Clock clock,
                          TransactionOperations transactions, BookingMetrics metrics, HoldMetrics holdMetrics,
                          PricingService pricing, PaymentGateway gateway, PaymentRepository payments,
                          PaymentMetrics paymentMetrics, BookingEvents events, UnrefundedCharges unrefunded,
                          EmailIndex emailIndex) {
        this.emailIndex = emailIndex;
        this.events = events;
        this.unrefunded = unrefunded;
        this.pricing = pricing;
        this.gateway = gateway;
        this.payments = payments;
        this.paymentMetrics = paymentMetrics;
        this.holdMetrics = holdMetrics;
        this.seats = seats;
        this.pnrs = pnrs;
        this.inventory = inventory;
        this.seatMaps = seatMaps;
        this.validator = validator;
        this.locators = locators;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public BookingResult createPnr(CreatePnrCommand command, String idempotencyKey) {
        try {
            BookingResult result = attempt(command, idempotencyKey);
            metrics.record(result.created() ? Outcome.CREATED : Outcome.REPLAYED);
            if (result.created() && command.holdId() != null) {
                holdMetrics.record(HoldMetrics.Outcome.CONVERTED);
            }
            log.atInfo()
                    .addKeyValue("pnr.locator", result.pnr().locator())
                    .addKeyValue("pnr.segments", command.segments().size())
                    .addKeyValue("pnr.passengers", command.passengers().size())
                    .log(result.created() ? "Booking created" : "Booking replayed");
            return result;
        } catch (SeatUnavailableException | OptimisticLockingFailureException e) {
            metrics.record(Outcome.SEAT_TAKEN);
            log.atInfo()
                    .addKeyValue("flights", flightNumbers(command))
                    .log("Booking refused: seat taken");
            throw e;
        } catch (InvalidBookingException | FlightNotFoundException | IdempotencyKeyReusedException
                 | PaymentDeclinedException | PriceChangedException e) {
            metrics.record(Outcome.REJECTED);
            log.atInfo().addKeyValue("reason", e.getClass().getSimpleName()).log("Booking rejected");
            throw e;
        }
    }

    private static String flightNumbers(CreatePnrCommand command) {
        return String.join(",", command.segments().stream().map(PnrSegment::flightNumber).toList());
    }

    private BookingResult attempt(CreatePnrCommand command, String idempotencyKey) {
        String keyHash = Sha256.hex(idempotencyKey);
        String fingerprint = RequestFingerprint.of(command);
        Optional<Pnr> replay = pnrs.findByIdempotencyKeyHash(keyHash);
        if (replay.isPresent()) {
            if (!replay.get().wasCreatedBy(fingerprint)) {
                throw new IdempotencyKeyReusedException("This Idempotency-Key was already used for a different booking");
            }
            return new BookingResult(PnrView.of(replay.get()), false);
        }
        validator.validate(command);
        ContactEmail contactEmail = new ContactEmail(command.contactEmail());
        // Outside the booking transaction on purpose: see SeatInventoryInitializer.
        command.segments().forEach(s -> inventory.ensureInventory(s.flightNumber(), s.departureDate()));
        // The price is the server's, never the request's (ADR-0015).
        Money price = pricing.quote(command.segments(), command.passengers().size());
        requireExpectedPrice(command, price);
        // Outside the booking transaction: this is a network call. The key is derived from the Idempotency-Key, so a
        // retry after a crash between "charged" and "booked" finds this charge instead of making another.
        PaymentGateway.Charge charge = charge(command.paymentToken(), price, "booking-" + keyHash);
        try {
            return transactions.execute(status -> book(command, contactEmail, keyHash, fingerprint, charge));
        } catch (RuntimeException failed) {
            refund(charge, failed); // paid but not booked: give the money back, then report the real failure
            throw failed;
        } finally {
            // Seats changed (or a conflict showed this instance's map is stale): show fresh maps next time.
            command.segments().forEach(s -> seatMaps.invalidate(s.flightNumber(), s.departureDate()));
        }
    }

    private void requireExpectedPrice(CreatePnrCommand command, Money price) {
        if (command.expectedAmount() != null && command.expectedAmount().compareTo(price.amount()) != 0) {
            paymentMetrics.record(PaymentMetrics.Outcome.PRICE_CHANGED);
            throw new PriceChangedException("The price is now " + price.amount() + " " + price.currency() + ", not "
                    + command.expectedAmount().setScale(2, java.math.RoundingMode.HALF_UP) + ". Nothing was charged.");
        }
    }

    private PaymentGateway.Charge charge(String paymentToken, Money price, String chargeKey) {
        try {
            PaymentGateway.Charge charge = gateway.charge(paymentToken, price, chargeKey);
            paymentMetrics.record(PaymentMetrics.Outcome.CAPTURED);
            return charge;
        } catch (PaymentDeclinedException e) {
            paymentMetrics.record(PaymentMetrics.Outcome.DECLINED);
            throw e;
        } catch (PaymentUnavailableException e) {
            paymentMetrics.record(PaymentMetrics.Outcome.UNAVAILABLE);
            throw e;
        }
    }

    /** Never throws: the caller is already reporting a failure, and a refund error must not hide it. */
    private void refund(PaymentGateway.Charge charge, RuntimeException bookingFailure) {
        try {
            gateway.refund(charge.id());
            paymentMetrics.record(PaymentMetrics.Outcome.REFUNDED);
            log.atInfo().addKeyValue("payment.charge", charge.id()).log("Charge refunded after a failed booking");
        } catch (RuntimeException refundFailed) {
            paymentMetrics.record(PaymentMetrics.Outcome.REFUND_FAILED);
            // Money taken, no booking, refund failed. Write it down so the retry job gives it back (ADR-0030);
            // only if even that fails does a person have to find the charge in the log (runbook: RefundFailed).
            log.atError().addKeyValue("payment.charge", charge.id()).setCause(refundFailed)
                    .log("Refund failed after a failed booking; the traveller has been charged");
            try {
                unrefunded.record(charge.id(), charge.amount(), bookingFailure.getClass().getSimpleName(), clock.instant());
            } catch (RuntimeException notRecorded) {
                log.atError().addKeyValue("payment.charge", charge.id()).setCause(notRecorded)
                        .log("Could not record the unrefunded charge; refund it by hand");
            }
        }
    }

    private BookingResult book(CreatePnrCommand command, ContactEmail contactEmail, String keyHash,
                               String fingerprint, PaymentGateway.Charge charge) {
        String locator = locators.next();
        List<PnrSegment> segments = command.segments();
        for (int i = 0; i < segments.size(); i++) {
            PnrSegment segment = segments.get(i);
            for (PassengerCommand passenger : command.passengers()) {
                String seatNumber = passenger.seats().get(i);
                SeatInventory seat = seats
                        .findByFlightNumberAndDepartureDateAndSeatNumber(
                                segment.flightNumber(), segment.departureDate(), seatNumber)
                        .orElseThrow(() -> new InvalidBookingException(
                                "Seat " + seatNumber + " does not exist on " + segment.flightNumber()));
                if (command.holdId() == null) {
                    seat.book(locator);
                } else {
                    seat.bookFromHold(command.holdId(), locator, clock.instant());
                }
            }
        }
        // Flush now so a concurrent booking of the same seat fails here, as an optimistic-lock conflict.
        seats.flush();

        List<PnrPassenger> passengers = command.passengers().stream()
                .map(p -> new PnrPassenger(p.firstName().strip(), p.lastName().strip(), p.seats()))
                .toList();
        Pnr pnr = Pnr.confirmed(locator, contactEmail, segments, passengers, clock.instant(), keyHash, fingerprint);
        pnr.indexContactEmail(emailIndex.of(contactEmail.value()));
        Pnr saved = pnrs.saveAndFlush(pnr);
        payments.saveAndFlush(Payment.captured(locator, charge.id(), charge.amount(), clock.instant()));
        // Same transaction as the booking: the event exists exactly when the booking does (ADR-0019).
        events.bookingConfirmed(saved, charge.amount());
        return new BookingResult(PnrView.of(saved), true);
    }
}
