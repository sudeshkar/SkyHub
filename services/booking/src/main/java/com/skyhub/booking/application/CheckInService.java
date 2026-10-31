package com.skyhub.booking.application;

import com.skyhub.booking.application.CheckInMetrics.Outcome;
import com.skyhub.booking.domain.CheckIn;
import com.skyhub.booking.domain.CheckInClosedException;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightCancelledException;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.NotCheckedInException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Online check-in (ADR-0013): the whole party on one flight at a time, inside the check-in window, with
 * boarding numbers that never repeat. Authorisation (who may touch which booking) is the security layer's job.
 */
@Service
public class CheckInService {

    private static final Logger log = LoggerFactory.getLogger(CheckInService.class);

    private final PnrRepository pnrs;
    private final CheckInRepository checkIns;
    private final BoardingSequence sequence;
    private final DisruptionRepository disruptions;
    private final FlightCatalog flights;
    private final BoardingPassSigner signer;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final CheckInWindow window;
    private final CheckInMetrics metrics;

    public CheckInService(PnrRepository pnrs, CheckInRepository checkIns, BoardingSequence sequence,
                          DisruptionRepository disruptions, FlightCatalog flights, BoardingPassSigner signer, Clock clock, TransactionOperations transactions,
                          CheckInWindow window, CheckInMetrics metrics) {
        this.pnrs = pnrs;
        this.checkIns = checkIns;
        this.sequence = sequence;
        this.disruptions = disruptions;
        this.flights = flights;
        this.signer = signer;
        this.clock = clock;
        this.transactions = transactions;
        this.window = window;
        this.metrics = metrics;
    }

    public CheckInResult checkIn(String locator, int segmentIndex) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        PnrSegment flight = flightAt(pnr, segmentIndex);

        // Checking in twice is not an error and must not renumber anyone, even after the window has closed.
        List<CheckIn> existing = rows(locator, segmentIndex);
        if (!existing.isEmpty()) {
            return replay(pnr, flight, existing);
        }

        Instant departure = departureOf(flight);
        try {
            window.requireOpen(departure, clock.instant());
        } catch (CheckInClosedException e) {
            metrics.record(Outcome.REFUSED);
            throw e;
        }

        try {
            List<CheckIn> created = transactions.execute(status -> create(locator, segmentIndex, flight));
            metrics.record(Outcome.CHECKED_IN);
            log.atInfo()
                    .addKeyValue("pnr.locator", locator)
                    .addKeyValue("flight", flight.flightNumber())
                    .addKeyValue("check_in.passengers", created.size())
                    .log("Checked in");
            return new CheckInResult(passes(pnr, flight, created), true);
        } catch (DataIntegrityViolationException lostRace) {
            // Another request checked this flight in first. The unique key stopped ours and rolled back its numbers.
            List<CheckIn> winners = rows(locator, segmentIndex);
            if (winners.isEmpty()) {
                throw lostRace;
            }
            return replay(pnr, flight, winners);
        }
    }

    /**
     * Takes the whole party off a flight's check-in (ADR-0055), so its seats can be changed again. Allowed until check-in closes, by the
     * revised time if the flight was delayed; a cancelled flight can always be checked out of, since a booking that is checked in cannot
     * be moved to another flight (ADR-0022). The old passes stop being shown, and checking in again numbers the party afresh.
     *
     * @return true if a check-in was removed, false if the flight was not checked in (so asking twice is harmless)
     */
    public boolean checkOut(String locator, int segmentIndex) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        PnrSegment flight = flightAt(pnr, segmentIndex);
        if (rows(locator, segmentIndex).isEmpty()) {
            return false;
        }
        requireCheckInNotClosedFor(flight);
        Long removed = transactions.execute(status -> checkIns.deleteByLocatorAndSegmentIndex(locator, segmentIndex));
        if (removed == null || removed == 0) {
            return false;   // another request got there first
        }
        metrics.record(Outcome.CHECKED_OUT);
        log.atInfo()
                .addKeyValue("pnr.locator", locator)
                .addKeyValue("flight", flight.flightNumber())
                .addKeyValue("check_in.passengers", removed)
                .log("Checked out");
        return true;
    }

    private void requireCheckInNotClosedFor(PnrSegment flight) {
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(flight.flightNumber(), flight.departureDate()).orElse(null);
        if (disruption != null && disruption.isCancelled()) {
            return;
        }
        Instant departure = disruption != null ? disruption.getRevisedDeparture() : flights.departure(flight.flightNumber(), flight.departureDate())
                .orElseThrow(() -> new FlightNotFoundException(flight.flightNumber() + " does not operate on " + flight.departureDate()));
        try {
            window.requireNotClosed(departure, clock.instant());
        } catch (CheckInClosedException e) {
            metrics.record(Outcome.REFUSED);
            throw e;
        }
    }

    /** A disruption beats the timetable: a cancelled flight has no check-in, a delayed one uses its revised time. */
    private Instant departureOf(PnrSegment flight) {
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(flight.flightNumber(), flight.departureDate())
                .orElse(null);
        if (disruption != null && disruption.isCancelled()) {
            metrics.record(Outcome.REFUSED);
            throw new FlightCancelledException(flight.flightNumber() + " on " + flight.departureDate() + " is cancelled");
        }
        if (disruption != null) {
            return disruption.getRevisedDeparture();
        }
        // Outside the transaction: this may be an HTTP call to flight-search.
        return flights.departure(flight.flightNumber(), flight.departureDate())
                .orElseThrow(() -> new FlightNotFoundException(
                        flight.flightNumber() + " does not operate on " + flight.departureDate()));
    }

    public CheckInResult boardingPasses(String locator, int segmentIndex) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        PnrSegment flight = flightAt(pnr, segmentIndex);
        List<CheckIn> existing = rows(locator, segmentIndex);
        if (existing.isEmpty()) {
            throw new NotCheckedInException("This flight is not checked in yet");
        }
        return new CheckInResult(passes(pnr, flight, existing), false);
    }

    private List<CheckIn> create(String locator, int segmentIndex, PnrSegment flight) {
        // Look again inside the transaction: the booking may have been cancelled since we first read it.
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        int party = pnr.getPassengers().size();
        int first = sequence.reserve(flight.flightNumber(), flight.departureDate(), party);
        Instant now = clock.instant();
        List<CheckIn> rows = IntStream.range(0, party)
                .mapToObj(p -> CheckIn.of(locator, segmentIndex, p, first + p, now))
                .toList();
        return checkIns.saveAllAndFlush(rows);
    }

    private CheckInResult replay(Pnr pnr, PnrSegment flight, List<CheckIn> existing) {
        metrics.record(Outcome.REPLAYED);
        return new CheckInResult(passes(pnr, flight, existing), false);
    }

    private List<CheckIn> rows(String locator, int segmentIndex) {
        return checkIns.findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(locator, segmentIndex);
    }

    private List<BoardingPass> passes(Pnr pnr, PnrSegment flight, List<CheckIn> rows) {
        return rows.stream().map(row -> pass(pnr, flight, row)).toList();
    }

    private BoardingPass pass(Pnr pnr, PnrSegment flight, CheckIn row) {
        PnrPassenger passenger = pnr.getPassengers().get(row.getPassengerIndex());
        String name = (passenger.getLastName() + "/" + passenger.getFirstName()).toUpperCase(Locale.ROOT);
        String seat = pnr.seatOf(row.getPassengerIndex(), row.getSegmentIndex());
        String payload = "M1%s %s %s %s %s %04d".formatted(
                name, pnr.getLocator(), flight.flightNumber(), flight.departureDate(), seat, row.getBoardingSequence());
        return new BoardingPass(name, pnr.getLocator(), flight.flightNumber(), flight.departureDate(), seat,
                row.getBoardingSequence(), signer.sign(payload));
    }

    private static PnrSegment flightAt(Pnr pnr, int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= pnr.getSegments().size()) {
            throw new InvalidBookingException("This booking has no flight " + segmentIndex);
        }
        return pnr.getSegments().get(segmentIndex);
    }

    private static PnrNotFoundException notFound() {
        return new PnrNotFoundException("No booking matches this reference");
    }
}
