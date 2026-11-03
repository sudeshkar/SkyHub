package com.skyhub.booking.application;

import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.NotBoardedException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Takes back a boarding that was recorded by mistake (ADR-0064). Who may do it is the security layer's rule (operations and
 * administrators, never a gate agent); this class only requires a reason and asks the port to change the boarding and record the
 * correction as one step. The passenger stays checked in, so they can board again.
 */
@Service
public class BoardingReversalService {

    /** Matches the column in the reversal table and the contract. */
    static final int MAX_REASON_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(BoardingReversalService.class);

    private final BoardedPassengers boarded;
    private final Clock clock;
    private final BoardingPassMetrics metrics;

    public BoardingReversalService(BoardedPassengers boarded, Clock clock, BoardingPassMetrics metrics) {
        this.boarded = boarded;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @param by the staff member, from their token
     * @throws InvalidBookingException when the reason is missing, blank or too long, before anything is looked at
     * @throws NotBoardedException when that passenger has not boarded that flight (including a second identical request)
     */
    public void undo(String locator, int segmentIndex, int passengerIndex, String reason, String by) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON_LENGTH) {
            throw new InvalidBookingException("A reason of 1 to " + MAX_REASON_LENGTH + " characters is needed to take a boarding back");
        }
        if (!boarded.undo(locator, segmentIndex, passengerIndex, by, why, clock.instant())) {
            metrics.reversed("not_boarded");
            throw new NotBoardedException("That passenger has not boarded this flight, so there is nothing to undo");
        }
        metrics.reversed("reversed");
        // The reason is free text typed by staff: only its length is logged, and it is never a metric label.
        log.atInfo().addKeyValue("pnr.locator", locator).addKeyValue("boarding.reversed_by", by)
                .addKeyValue("boarding.reason_length", why.length()).log("Boarding taken back");
    }
}
