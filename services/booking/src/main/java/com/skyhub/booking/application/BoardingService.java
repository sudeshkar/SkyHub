package com.skyhub.booking.application;

import com.skyhub.booking.application.BoardingPassVerifier.Reason;
import com.skyhub.booking.application.BoardingPassVerifier.Verdict;
import com.skyhub.booking.domain.BoardingWindow;
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

    private final BoardingPassVerifier verifier;
    private final BoardedPassengers boarded;
    private final Clock clock;
    private final BoardingPassMetrics metrics;
    private final ExpectedDepartures departures;
    private final BoardingWindow window;

    public BoardingService(BoardingPassVerifier verifier, BoardedPassengers boarded, Clock clock, BoardingPassMetrics metrics,
                           ExpectedDepartures departures, BoardingWindow window) {
        this.verifier = verifier;
        this.boarded = boarded;
        this.clock = clock;
        this.metrics = metrics;
        this.departures = departures;
        this.window = window;
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

    /** @return why boarding is shut for this pass's flight right now, or null when it is open */
    private Reason outOfWindow(BoardingPass pass) {
        // A flight cancelled in the moment since the pass was checked has no departure: refuse it as the check would have.
        Optional<Instant> departure = departures.of(pass.flightNumber(), pass.departureDate());
        if (departure.isEmpty()) {
            return Reason.FLIGHT_CANCELLED;
        }
        return switch (window.stateAt(departure.get(), clock.instant())) {
            case NOT_YET_OPEN -> Reason.BOARDING_NOT_OPEN;
            case CLOSED -> Reason.BOARDING_CLOSED;
            case OPEN -> null;
        };
    }
}
