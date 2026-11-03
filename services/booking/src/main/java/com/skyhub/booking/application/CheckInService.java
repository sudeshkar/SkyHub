package com.skyhub.booking.application;

import com.skyhub.booking.application.CheckInMetrics.Outcome;
import com.skyhub.booking.domain.AlreadyBoardedException;
import com.skyhub.booking.domain.CheckIn;
import com.skyhub.booking.domain.CheckInClosedException;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.domain.FlightCancelledException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.NotCheckedInException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.CheckInRepository;
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
    private final ExpectedDepartures departures;
    private final BoardingPassSigner signer;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final CheckInWindow window;
    private final CheckInMetrics metrics;
    private final BoardedPassengers boarded;
    private final BookingEvents events;

    public CheckInService(PnrRepository pnrs, CheckInRepository checkIns, BoardingSequence sequence,
                          ExpectedDepartures departures, BoardingPassSigner signer, Clock clock, TransactionOperations transactions,
                          CheckInWindow window, CheckInMetrics metrics, BoardedPassengers boarded, BookingEvents events) {
        this.pnrs = pnrs;
        this.checkIns = checkIns;
        this.sequence = sequence;
        this.departures = departures;
        this.signer = signer;
        this.clock = clock;
        this.transactions = transactions;
        this.window = window;
        this.metrics = metrics;
        this.boarded = boarded;
        this.events = events;
    }

    /** Checks in everyone on the booking who is not yet in. */
    public CheckInResult checkIn(String locator, int segmentIndex) {
        return checkIn(locator, segmentIndex, List.of());
    }

    /**
     * Checks in the named passengers on one flight, or everyone not yet in when none are named (ADR-0058). A passenger who is already in
     * is left as they are and is not renumbered; only the newly checked-in get boarding numbers, in one block. The answer holds the
     * passes of everyone who is checked in on the flight afterwards.
     */
    public CheckInResult checkIn(String locator, int segmentIndex, List<Integer> passengerIndexes) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        PnrSegment flight = flightAt(pnr, segmentIndex);
        List<Integer> wanted = passengersAsked(pnr, passengerIndexes);

        // Checking in twice is not an error and must not renumber anyone, even after the window has closed.
        List<CheckIn> existing = rows(locator, segmentIndex);
        if (allIn(existing, wanted)) {
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
            List<CheckIn> created = transactions.execute(status -> create(locator, segmentIndex, flight, wanted, departure));
            metrics.record(Outcome.CHECKED_IN);
            log.atInfo()
                    .addKeyValue("pnr.locator", locator)
                    .addKeyValue("flight", flight.flightNumber())
                    .addKeyValue("check_in.passengers", created.size())
                    .log("Checked in");
            return new CheckInResult(passes(pnr, flight, rows(locator, segmentIndex)), true);
        } catch (DataIntegrityViolationException lostRace) {
            // Another request checked one of these in first. The unique key stopped ours and rolled back its numbers.
            List<CheckIn> winners = rows(locator, segmentIndex);
            if (!allIn(winners, wanted)) {
                throw lostRace;
            }
            return replay(pnr, flight, winners);
        }
    }

    /** The passengers a request means, once each and in booking order: the named ones, or everyone when none are named. */
    private static List<Integer> passengersAsked(Pnr pnr, List<Integer> named) {
        int party = pnr.getPassengers().size();
        if (named == null || named.isEmpty()) {
            return IntStream.range(0, party).boxed().toList();
        }
        for (int index : named) {
            if (index < 0 || index >= party) {
                throw new InvalidBookingException("This booking has no passenger " + index);
            }
        }
        return named.stream().distinct().sorted().toList();
    }

    private static boolean allIn(List<CheckIn> rows, List<Integer> wanted) {
        return rows.stream().map(CheckIn::getPassengerIndex).collect(java.util.stream.Collectors.toSet()).containsAll(wanted);
    }

    /** Checks out everyone who is checked in on the flight. */
    public boolean checkOut(String locator, int segmentIndex) {
        return checkOut(locator, segmentIndex, List.of());
    }

    /**
     * Takes the named passengers off a flight's check-in, or everyone when none are named (ADR-0055, ADR-0058), so their seats can be
     * changed again. Allowed until check-in closes, by the revised time if the flight was delayed; a cancelled flight can always be checked
     * out of, since a booking that is checked in cannot be moved to another flight (ADR-0022). Their old passes stop being shown, and
     * checking in again numbers them afresh. Passengers who are not checked in are left alone.
     *
     * @return true if a check-in was removed, false if none of them was checked in (so asking twice is harmless)
     */
    public boolean checkOut(String locator, int segmentIndex, List<Integer> passengerIndexes) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        PnrSegment flight = flightAt(pnr, segmentIndex);
        List<Integer> named = passengersAsked(pnr, passengerIndexes);
        if (rows(locator, segmentIndex).stream().noneMatch(row -> named.contains(row.getPassengerIndex()))) {
            return false;
        }
        if (boarded.anyBoarded(locator, segmentIndex, named)) {
            // Someone on the aircraft cannot check out (ADR-0060). Nobody is removed, so a party cannot be half checked out by accident.
            String who = named.stream().filter(p -> boarded.isBoarded(locator, segmentIndex, p))
                    .map(p -> pnr.getPassengers().get(p).getFirstName()).collect(java.util.stream.Collectors.joining(", "));
            throw new AlreadyBoardedException(who + " has already boarded " + flight.flightNumber() + ", so cannot check out");
        }
        requireCheckInNotClosedFor(flight);
        Long removed = transactions.execute(status -> checkIns.deleteByLocatorAndSegmentIndexAndPassengerIndexIn(locator, segmentIndex, named));
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
        // A cancelled flight has no departure, and a traveller can always check out of it (ADR-0055).
        Instant departure = departures.of(flight.flightNumber(), flight.departureDate()).orElse(null);
        if (departure == null) {
            return;
        }
        try {
            window.requireNotClosed(departure, clock.instant());
        } catch (CheckInClosedException e) {
            metrics.record(Outcome.REFUSED);
            throw e;
        }
    }

    /** A disruption beats the timetable: a cancelled flight has no check-in, a delayed one uses its revised time. */
    private Instant departureOf(PnrSegment flight) {
        // Outside the transaction: this may be an HTTP call to flight-search.
        return departures.of(flight.flightNumber(), flight.departureDate()).orElseThrow(() -> {
            metrics.record(Outcome.REFUSED);
            return new FlightCancelledException(flight.flightNumber() + " on " + flight.departureDate() + " is cancelled");
        });
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

    private List<CheckIn> create(String locator, int segmentIndex, PnrSegment flight, List<Integer> wanted, Instant departure) {
        // Look again inside the transaction: the booking may have been cancelled since we first read it.
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(CheckInService::notFound);
        pnr.requireActive();
        java.util.Set<Integer> already = rows(locator, segmentIndex).stream().map(CheckIn::getPassengerIndex)
                .collect(java.util.stream.Collectors.toSet());
        List<Integer> toCheckIn = wanted.stream().filter(p -> !already.contains(p)).toList();
        int first = sequence.reserve(flight.flightNumber(), flight.departureDate(), toCheckIn.size());
        Instant now = clock.instant();
        List<CheckIn> rows = IntStream.range(0, toCheckIn.size())
                .mapToObj(i -> CheckIn.of(locator, segmentIndex, toCheckIn.get(i), first + i, now))
                .toList();
        List<CheckIn> saved = checkIns.saveAllAndFlush(rows);
        // Same transaction as the rows: the confirmation exists exactly when the check-in does, and not for a lost race, which throws above.
        events.checkedIn(pnr, flight, passes(pnr, flight, saved), departure, departure.minus(window.closesBefore()));
        return saved;
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
        String payload = BoardingPassPayload.of(
                name, pnr.getLocator(), flight.flightNumber(), flight.departureDate(), seat, row.getBoardingSequence());
        return new BoardingPass(name, row.getPassengerIndex(), pnr.getLocator(), flight.flightNumber(), flight.departureDate(), seat,
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
