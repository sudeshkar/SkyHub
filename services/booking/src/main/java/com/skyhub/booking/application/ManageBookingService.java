package com.skyhub.booking.application;

import com.skyhub.booking.application.ManageMetrics.Action;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Manage-booking use cases. Authorisation (who may manage which booking) is enforced by the security
 * layer before these methods run; opening a session is the one step that checks ownership here, by
 * last name. Returns read models only. Every change is one transaction over the booking and its seats.
 */
@Service
public class ManageBookingService {

    private static final Logger log = LoggerFactory.getLogger(ManageBookingService.class);

    private final PnrRepository pnrs;
    private final SeatInventoryRepository seats;
    private final SeatMapCache seatMaps;
    private final ManageTokenIssuer tokens;
    private final ManageMetrics metrics;
    private final TransactionOperations transactions;

    public ManageBookingService(PnrRepository pnrs, SeatInventoryRepository seats, SeatMapCache seatMaps,
                                ManageTokenIssuer tokens, ManageMetrics metrics, TransactionOperations transactions) {
        this.pnrs = pnrs;
        this.seats = seats;
        this.seatMaps = seatMaps;
        this.tokens = tokens;
        this.metrics = metrics;
        this.transactions = transactions;
    }

    /** Unknown reference and wrong last name are indistinguishable to the caller. */
    public ManageSession openSession(String locator, String lastName) {
        Pnr pnr = pnrs.findByLocator(locator).filter(found -> found.hasPassengerWithLastName(lastName)).orElse(null);
        if (pnr == null) {
            metrics.record(Action.SESSION_DENIED);
            log.atInfo().log("Manage session denied");
            throw new PnrNotFoundException("No booking matches this reference and last name");
        }
        metrics.record(Action.SESSION_OPENED);
        log.atInfo().addKeyValue("pnr.locator", locator).log("Manage session opened");
        return new ManageSession(tokens.issue(locator), PnrView.of(pnr));
    }

    public PnrView find(String locator) {
        return PnrView.of(pnrs.findByLocator(locator).orElseThrow(ManageBookingService::notFound));
    }

    public PnrView cancel(String locator) {
        List<PnrSegment> flights = null;
        try {
            Cancellation result = transactions.execute(status -> cancelInTransaction(locator));
            flights = result.flights();
            if (result.changed()) {
                metrics.record(Action.CANCELLED);
                log.atInfo().addKeyValue("pnr.locator", locator).log("Booking cancelled");
            }
            return result.view();
        } finally {
            if (flights != null) {
                flights.forEach(f -> seatMaps.invalidate(f.flightNumber(), f.departureDate()));
            }
        }
    }

    public PnrView changeSeat(String locator, SeatChangeCommand command) {
        PnrSegment flight = null;
        try {
            SeatChange result = transactions.execute(status -> changeInTransaction(locator, command));
            flight = result.flight();
            if (result.changed()) {
                metrics.record(Action.SEAT_CHANGED);
                log.atInfo()
                        .addKeyValue("pnr.locator", locator)
                        .addKeyValue("flight", flight.flightNumber())
                        .log("Seat changed");
            }
            return result.view();
        } catch (SeatUnavailableException | OptimisticLockingFailureException e) {
            metrics.record(Action.SEAT_TAKEN);
            throw e;
        } finally {
            if (flight != null) {
                seatMaps.invalidate(flight.flightNumber(), flight.departureDate());
            }
        }
    }

    private Cancellation cancelInTransaction(String locator) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(ManageBookingService::notFound);
        List<PnrSegment> flights = pnr.getSegments();
        if (!pnr.cancel()) {
            return new Cancellation(PnrView.of(pnr), flights, false);
        }
        for (int s = 0; s < flights.size(); s++) {
            for (int p = 0; p < pnr.getPassengers().size(); p++) {
                seatOf(flights.get(s), pnr.seatOf(p, s)).release(locator);
            }
        }
        return new Cancellation(PnrView.of(pnrs.saveAndFlush(pnr)), flights, true);
    }

    private SeatChange changeInTransaction(String locator, SeatChangeCommand command) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(ManageBookingService::notFound);
        // Validate positions and cancelled state before touching any seat.
        String current = pnr.seatOf(command.passengerIndex(), command.segmentIndex());
        pnr.requireActive();
        PnrSegment flight = pnr.getSegments().get(command.segmentIndex());
        if (current.equals(command.seatNumber())) {
            return new SeatChange(PnrView.of(pnr), flight, false);
        }
        SeatInventory target = seatOf(flight, command.seatNumber());
        target.book(locator);
        pnr.changeSeat(command.passengerIndex(), command.segmentIndex(), command.seatNumber());
        seatOf(flight, current).release(locator);
        return new SeatChange(PnrView.of(pnrs.saveAndFlush(pnr)), flight, true);
    }

    private SeatInventory seatOf(PnrSegment flight, String seatNumber) {
        return seats.findByFlightNumberAndDepartureDateAndSeatNumber(flight.flightNumber(), flight.departureDate(), seatNumber)
                .orElseThrow(() -> new InvalidBookingException(
                        "Seat " + seatNumber + " does not exist on " + flight.flightNumber()));
    }

    private static PnrNotFoundException notFound() {
        return new PnrNotFoundException("No booking matches this reference");
    }

    private record Cancellation(PnrView view, List<PnrSegment> flights, boolean changed) {
    }

    private record SeatChange(PnrView view, PnrSegment flight, boolean changed) {
    }
}
