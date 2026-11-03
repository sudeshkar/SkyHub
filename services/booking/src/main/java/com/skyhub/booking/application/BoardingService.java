package com.skyhub.booking.application;

import com.skyhub.booking.application.BoardingPassVerifier.Reason;
import com.skyhub.booking.application.BoardingPassVerifier.Verdict;
import com.skyhub.booking.domain.BoardingWindow;
import com.skyhub.booking.domain.BoardingWindows;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A gate scans a pass and the passenger boards (ADR-0060). The pass must be good (ADR-0056); a passenger boards once, so a pass that has
 * been used is refused the second time, and who made the first scan is kept. Checking a pass (ADR-0056) never boards anyone.
 */
@Service
public class BoardingService {

    private static final Logger log = LoggerFactory.getLogger(BoardingService.class);

    /** The longest reason for boarding someone late, as in the reversal table and the contract (ADR-0076). */
    static final int MAX_LATE_REASON_LENGTH = 200;

    private final BoardingPassVerifier verifier;
    private final BoardedPassengers boarded;
    private final Clock clock;
    private final BoardingPassMetrics metrics;
    private final ExpectedDepartures departures;
    private final FlightCatalog flights;
    private final BoardingWindows windows;

    public BoardingService(BoardingPassVerifier verifier, BoardedPassengers boarded, Clock clock, BoardingPassMetrics metrics,
                           ExpectedDepartures departures, FlightCatalog flights, BoardingWindows windows) {
        this.verifier = verifier;
        this.boarded = boarded;
        this.clock = clock;
        this.metrics = metrics;
        this.departures = departures;
        this.flights = flights;
        this.windows = windows;
    }

    /**
     * @param by the staff member at the gate, from their token
     * @return a good verdict with the pass if the passenger has boarded now; otherwise the reason they did not, with nothing about them
     */
    public Verdict board(String barcode, String by) {
        Verdict verdict = verifier.check(barcode);
        if (!verdict.valid()) {
            metrics.boarded(verdict.reason().tag());
            return verdict;
        }
        BoardingPass pass = verdict.pass();
        // Only a good pass reaches here, so the timetable is asked about real flights only, and once per scan (never per booking).
        Reason outOfWindow = outOfWindow(pass);
        if (outOfWindow != null) {
            metrics.boarded(outOfWindow.tag());
            return Verdict.refused(outOfWindow);
        }
        // The check and this write are two steps: a second gate scanning at the same instant loses on the primary key, not on the check.
        boolean first = boarded.board(pass.locator(), verdict.segmentIndex(), pass.passengerIndex(), by, clock.instant());
        if (!first) {
            metrics.boarded(Reason.ALREADY_BOARDED.tag());
            return Verdict.refused(Reason.ALREADY_BOARDED);
        }
        metrics.boarded("boarded");
        log.atInfo().addKeyValue("pnr.locator", pass.locator()).addKeyValue("flight", pass.flightNumber())
                .addKeyValue("boarding.by", by).log("Passenger boarded");
        return verdict;
    }

    /**
     * Boards a passenger after the gate has closed (ADR-0076): for a good pass, once boarding has closed and while the flight has not yet
     * left. It is a second door into the same boarding: the same checks of the pass, the same one row for each passenger, and the first
     * boarding still counts. It is refused while boarding is still open (the ordinary way is the right one), before it has opened, and once
     * the flight is due to have left. The reason is kept with who made it and when; it is free text, so only its length is logged.
     * Whether this person may is the security layer's rule (operations and administrators, never a gate agent).
     *
     * @param by the staff member, from their token
     * @throws com.skyhub.booking.domain.InvalidBookingException when the reason is missing, blank or too long, before anything is looked at
     */
    public Verdict boardLate(String barcode, String reason, String by) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_LATE_REASON_LENGTH) {
            throw new com.skyhub.booking.domain.InvalidBookingException(
                    "A reason of 1 to " + MAX_LATE_REASON_LENGTH + " characters is needed to board a passenger after the gate has closed");
        }
        Verdict verdict = verifier.check(barcode);
        if (!verdict.valid()) {
            metrics.boarded(verdict.reason().tag());
            return verdict;
        }
        BoardingPass pass = verdict.pass();
        Reason refusal = lateRefusal(pass);
        if (refusal != null) {
            metrics.boarded(refusal.tag());
            return Verdict.refused(refusal);
        }
        boolean first = boarded.boardLate(pass.locator(), verdict.segmentIndex(), pass.passengerIndex(), by, clock.instant(), why);
        if (!first) {
            metrics.boarded(Reason.ALREADY_BOARDED.tag());
            return Verdict.refused(Reason.ALREADY_BOARDED);
        }
        metrics.boarded("boarded_late");
        log.atInfo().addKeyValue("pnr.locator", pass.locator()).addKeyValue("flight", pass.flightNumber())
                .addKeyValue("boarding.by", by).addKeyValue("boarding.late_reason_length", why.length()).log("Passenger boarded late");
        return verdict;
    }

    /** @return why a late boarding is not allowed for this pass's flight right now, or null when it is */
    private Reason lateRefusal(BoardingPass pass) {
        Optional<Instant> departure = departures.of(pass.flightNumber(), pass.departureDate());
        if (departure.isEmpty()) {
            return Reason.FLIGHT_CANCELLED;
        }
        Instant now = clock.instant();
        return switch (windowOf(pass).stateAt(departure.get(), now)) {
            case NOT_YET_OPEN -> Reason.BOARDING_NOT_OPEN;
            case OPEN -> Reason.BOARDING_STILL_OPEN;
            case CLOSED -> now.isBefore(departure.get()) ? null : Reason.FLIGHT_DEPARTED;
        };
    }

    /**
     * The window of this pass's flight: the one set for its route, or the standard one when the route is not known (ADR-0077). One more
     * answer from the timetable, which is cached, for each scan that reaches the window (never for a pass that is not good).
     */
    private BoardingWindow windowOf(BoardingPass pass) {
        return flights.find(pass.flightNumber(), pass.departureDate())
                .map(info -> windows.forRoute(info.origin(), info.destination()))
                .orElseGet(windows::standard);
    }

    /** @return why boarding is shut for this pass's flight right now, or null when it is open */
    private Reason outOfWindow(BoardingPass pass) {
        // A flight cancelled in the moment since the pass was checked has no departure: refuse it as the check would have.
        Optional<Instant> departure = departures.of(pass.flightNumber(), pass.departureDate());
        if (departure.isEmpty()) {
            return Reason.FLIGHT_CANCELLED;
        }
        return switch (windowOf(pass).stateAt(departure.get(), clock.instant())) {
            case NOT_YET_OPEN -> Reason.BOARDING_NOT_OPEN;
            case CLOSED -> Reason.BOARDING_CLOSED;
            case OPEN -> null;
        };
    }
}
