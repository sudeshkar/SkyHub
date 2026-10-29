package com.skyhub.booking.application;

import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.IdempotencyKeyReusedException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.LocatorGenerator;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Create-PNR workflow (docs/design/02-lld.md §4.5). Returns read models only; entities stay inside.
 */
@Service
public class BookingService {

    private final SeatInventoryRepository seats;
    private final PnrRepository pnrs;
    private final SeatInventoryInitializer inventory;
    private final CreatePnrValidator validator;
    private final LocatorGenerator locators;
    private final Clock clock;
    private final TransactionOperations transactions;

    public BookingService(SeatInventoryRepository seats, PnrRepository pnrs, SeatInventoryInitializer inventory,
                          CreatePnrValidator validator, LocatorGenerator locators, Clock clock,
                          TransactionOperations transactions) {
        this.seats = seats;
        this.pnrs = pnrs;
        this.inventory = inventory;
        this.validator = validator;
        this.locators = locators;
        this.clock = clock;
        this.transactions = transactions;
    }

    public BookingResult createPnr(CreatePnrCommand command, String idempotencyKey) {
        String fingerprint = RequestFingerprint.of(command);
        Optional<Pnr> replay = pnrs.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            if (!replay.get().wasCreatedBy(fingerprint)) {
                throw new IdempotencyKeyReusedException("This Idempotency-Key was already used for a different booking");
            }
            return new BookingResult(PnrView.of(replay.get()), false);
        }
        validator.validate(command);
        // Outside the booking transaction on purpose: see SeatInventoryInitializer.
        command.segments().forEach(s -> inventory.ensureInventory(s.flightNumber(), s.departureDate()));
        return transactions.execute(status -> book(command, idempotencyKey, fingerprint));
    }

    public PnrView findPnr(String locator, String lastName) {
        return pnrs.findByLocator(locator)
                .filter(pnr -> pnr.hasPassengerWithLastName(lastName))
                .map(PnrView::of)
                .orElseThrow(() -> new PnrNotFoundException("No booking matches this reference and last name"));
    }

    private BookingResult book(CreatePnrCommand command, String idempotencyKey, String fingerprint) {
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
                seat.book(locator);
            }
        }
        // Flush now so a concurrent booking of the same seat fails here, as an optimistic-lock conflict.
        seats.flush();

        List<PnrPassenger> passengers = command.passengers().stream()
                .map(p -> new PnrPassenger(p.firstName().strip(), p.lastName().strip(), p.seats()))
                .toList();
        Pnr pnr = Pnr.confirmed(locator, command.contactEmail().strip(), segments, passengers, clock.instant(),
                idempotencyKey, fingerprint);
        return new BookingResult(PnrView.of(pnrs.saveAndFlush(pnr)), true);
    }
}
