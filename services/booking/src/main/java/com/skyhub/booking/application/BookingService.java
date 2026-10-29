package com.skyhub.booking.application;

import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.IdempotencyKeyReusedException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.LocatorGenerator;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
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

    public BookingService(SeatInventoryRepository seats, PnrRepository pnrs, SeatInventoryInitializer inventory,
                          SeatMapCache seatMaps, CreatePnrValidator validator, LocatorGenerator locators, Clock clock,
                          TransactionOperations transactions, BookingMetrics metrics, HoldMetrics holdMetrics) {
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
        } catch (InvalidBookingException | FlightNotFoundException | IdempotencyKeyReusedException e) {
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
        try {
            return transactions.execute(status -> book(command, contactEmail, keyHash, fingerprint));
        } finally {
            // Seats changed (or a conflict showed this instance's map is stale): show fresh maps next time.
            command.segments().forEach(s -> seatMaps.invalidate(s.flightNumber(), s.departureDate()));
        }
    }

    private BookingResult book(CreatePnrCommand command, ContactEmail contactEmail, String keyHash,
                               String fingerprint) {
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
        return new BookingResult(PnrView.of(pnrs.saveAndFlush(pnr)), true);
    }
}
