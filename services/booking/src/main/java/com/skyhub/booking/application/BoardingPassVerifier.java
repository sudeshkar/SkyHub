package com.skyhub.booking.application;

import com.skyhub.booking.domain.CheckIn;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answers a gate's question "is this scanned pass still good?" (ADR-0056). A good pass is signed by us AND is exactly the pass that
 * would be issued for the booking now. The signature alone proves we issued it, never that it still applies: a passenger who has
 * checked out (ADR-0055) or checked in again holds a pass that is still genuinely signed, and so is one for a cancelled booking or
 * flight. A refused pass says only why, never who it was for.
 */
@Service
public class BoardingPassVerifier {

    /** Longer than any pass we sign by a wide margin; anything longer is not one. */
    static final int MAX_LENGTH = 300;

    private static final Logger log = LoggerFactory.getLogger(BoardingPassVerifier.class);

    public enum Reason {
        /** Not signed by us, changed since, or not a pass at all. */
        BAD_SIGNATURE,
        /** Signed by us but its booking does not exist (for example, issued by another environment with the same key). */
        UNKNOWN_BOOKING,
        BOOKING_CANCELLED,
        FLIGHT_CANCELLED,
        /** The flight is not checked in: never was, or checked out (ADR-0055), or the flight is not on the booking. */
        NOT_CHECKED_IN,
        /** A different pass is current: a new seat or a new check-in since. */
        SUPERSEDED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code pass} is set only for a good pass. */
    public record Verdict(boolean valid, Reason reason, BoardingPass pass) {
        static Verdict good(BoardingPass pass) {
            return new Verdict(true, null, pass);
        }

        static Verdict refused(Reason reason) {
            return new Verdict(false, reason, null);
        }
    }

    private final PnrRepository pnrs;
    private final CheckInRepository checkIns;
    private final DisruptionRepository disruptions;
    private final BoardingPassSigner signer;
    private final BoardingPassMetrics metrics;

    public BoardingPassVerifier(PnrRepository pnrs, CheckInRepository checkIns, DisruptionRepository disruptions, BoardingPassSigner signer,
                                BoardingPassMetrics metrics) {
        this.pnrs = pnrs;
        this.checkIns = checkIns;
        this.disruptions = disruptions;
        this.signer = signer;
        this.metrics = metrics;
    }

    public Verdict verify(String barcode) {
        Verdict verdict = decide(barcode);
        metrics.verified(verdict.valid() ? "valid" : verdict.reason().tag());
        log.atInfo().addKeyValue("boarding_pass.valid", verdict.valid())
                .addKeyValue("boarding_pass.reason", verdict.reason() == null ? "" : verdict.reason().tag())
                .log("Boarding pass checked");
        return verdict;
    }

    private Verdict decide(String barcode) {
        // Nothing is looked up for something we did not sign: a gate cannot be used to probe bookings with made-up text.
        if (barcode == null || barcode.isEmpty() || barcode.length() > MAX_LENGTH || !signer.verify(barcode)) {
            return Verdict.refused(Reason.BAD_SIGNATURE);
        }
        Optional<BoardingPassPayload.Parsed> parsed = BoardingPassPayload.parse(barcode);
        if (parsed.isEmpty()) {
            return Verdict.refused(Reason.BAD_SIGNATURE);
        }
        BoardingPassPayload.Parsed asked = parsed.get();
        Pnr pnr = pnrs.findByLocator(asked.locator()).orElse(null);
        if (pnr == null) {
            return Verdict.refused(Reason.UNKNOWN_BOOKING);
        }
        if (pnr.getStatus() == PnrStatus.CANCELLED) {
            return Verdict.refused(Reason.BOOKING_CANCELLED);
        }
        int segmentIndex = segmentOf(pnr, asked);
        if (segmentIndex < 0) {
            return Verdict.refused(Reason.NOT_CHECKED_IN);
        }
        PnrSegment flight = pnr.getSegments().get(segmentIndex);
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(flight.flightNumber(), flight.departureDate()).orElse(null);
        if (disruption != null && disruption.isCancelled()) {
            return Verdict.refused(Reason.FLIGHT_CANCELLED);
        }
        List<CheckIn> rows = checkIns.findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(asked.locator(), segmentIndex);
        if (rows.isEmpty()) {
            return Verdict.refused(Reason.NOT_CHECKED_IN);
        }
        // The pass as it would be issued right now, for each passenger who is checked in; the scanned one must be one of them.
        for (CheckIn row : rows) {
            BoardingPass current = currentPass(pnr, flight, row, barcode);
            if (BoardingPassPayload.of(current.passengerName(), current.locator(), current.flightNumber(), current.departureDate(),
                    current.seatNumber(), current.sequence()).equals(asked.payload())) {
                return Verdict.good(current);
            }
        }
        return Verdict.refused(Reason.SUPERSEDED);
    }

    private static int segmentOf(Pnr pnr, BoardingPassPayload.Parsed asked) {
        for (int i = 0; i < pnr.getSegments().size(); i++) {
            PnrSegment s = pnr.getSegments().get(i);
            if (s.flightNumber().equals(asked.flightNumber()) && s.departureDate().equals(asked.departureDate())) {
                return i;
            }
        }
        return -1;
    }

    private BoardingPass currentPass(Pnr pnr, PnrSegment flight, CheckIn row, String barcode) {
        PnrPassenger passenger = pnr.getPassengers().get(row.getPassengerIndex());
        String name = (passenger.getLastName() + "/" + passenger.getFirstName()).toUpperCase(Locale.ROOT);
        String seat = pnr.seatOf(row.getPassengerIndex(), row.getSegmentIndex());
        return new BoardingPass(name, pnr.getLocator(), flight.flightNumber(), flight.departureDate(), seat, row.getBoardingSequence(), barcode);
    }
}
