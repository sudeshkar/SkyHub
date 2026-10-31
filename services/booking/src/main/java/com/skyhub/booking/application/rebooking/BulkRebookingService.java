package com.skyhub.booking.application.rebooking;

import com.skyhub.booking.application.FlightCatalogUnavailableException;
import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.SeatUnavailableException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

/**
 * Moves many bookings in one request (ADR-0035) by running the ordinary single move for each, so the rules, the seat choice, the
 * audit and the email are the same code and cannot drift apart. Each move is its own transaction: one that is refused or loses a
 * seat race changes nothing and the rest carry on. When the timetable stops answering, or something unexpected fails, the
 * remaining moves are not tried (they would only repeat the failure), and are reported as such.
 *
 * <p>Moves run one after another on purpose: moves onto one flight compete for the same seats, so running them in parallel
 * would turn most of them into conflicts. Cost is O(moves) calls of {@link RebookingService#rebook}, bounded by {@link #MAX_MOVES}.
 */
@Service
public class BulkRebookingService {

    /** Most moves in one request. Keeps a request short and the answer easy to read. */
    public static final int MAX_MOVES = 50;

    private static final Logger log = LoggerFactory.getLogger(BulkRebookingService.class);

    private final RebookingService rebooking;

    public BulkRebookingService(RebookingService rebooking) {
        this.rebooking = rebooking;
    }

    /** @return one result per move, in the order given */
    public List<MoveResult> rebookAll(List<RebookCommand> moves) {
        requireWellFormed(moves);
        List<MoveResult> results = new ArrayList<>(moves.size());
        boolean stopped = false;
        for (RebookCommand move : moves) {
            MoveResult result = stopped ? MoveResult.of(move, MoveOutcome.NOT_ATTEMPTED, "Not tried: an earlier move could not be completed.")
                    : attempt(move);
            stopped = stopped || result.outcome() == MoveOutcome.UNAVAILABLE || result.outcome() == MoveOutcome.FAILED;
            results.add(result);
        }
        summarise(moves, results);
        return results;
    }

    private MoveResult attempt(RebookCommand move) {
        try {
            RebookingResult done = rebooking.rebook(move);
            return new MoveResult(done.locator(), done.segmentIndex(), done.changed() ? MoveOutcome.MOVED : MoveOutcome.UNCHANGED,
                    null, done.seats());
        } catch (RebookingRefusedException | InvalidBookingException | BookingCancelledException refused) {
            return MoveResult.of(move, MoveOutcome.REFUSED, refused.getMessage());
        } catch (PnrNotFoundException unknown) {
            return MoveResult.of(move, MoveOutcome.NOT_FOUND, "No booking matches this reference");
        } catch (SeatUnavailableException | ConcurrencyFailureException lostTheRace) {
            return MoveResult.of(move, MoveOutcome.CONFLICT, "The seats changed while this booking was being moved; try again.");
        } catch (FlightCatalogUnavailableException timetableDown) {
            log.atWarn().addKeyValue("pnr.locator", move.locator()).log("Bulk rebooking stopped: the timetable is not answering");
            return MoveResult.of(move, MoveOutcome.UNAVAILABLE, "The timetable is not answering.");
        } catch (RuntimeException unexpected) {
            // The cause can name anything; it goes to the log, never to the caller.
            log.atError().addKeyValue("pnr.locator", move.locator()).setCause(unexpected).log("Bulk rebooking stopped: a move failed unexpectedly");
            return MoveResult.of(move, MoveOutcome.FAILED, "This move could not be completed.");
        }
    }

    private static void requireWellFormed(List<RebookCommand> moves) {
        if (moves == null || moves.isEmpty() || moves.size() > MAX_MOVES) {
            throw new InvalidBookingException("Move between 1 and " + MAX_MOVES + " bookings at a time");
        }
        Set<String> seen = new HashSet<>();
        for (RebookCommand move : moves) {
            if (!seen.add(move.locator() + "/" + move.segmentIndex())) {
                throw new InvalidBookingException(move.locator() + " flight " + move.segmentIndex() + " is listed twice");
            }
        }
    }

    private static void summarise(List<RebookCommand> moves, List<MoveResult> results) {
        Map<MoveOutcome, Integer> counts = new EnumMap<>(MoveOutcome.class);
        results.forEach(r -> counts.merge(r.outcome(), 1, Integer::sum));
        log.atInfo().addKeyValue("rebooking.bulk.moves", moves.size()).addKeyValue("rebooking.bulk.by", moves.getFirst().rebookedBy())
                .addKeyValue("rebooking.bulk.outcomes", counts).log("Bulk rebooking finished");
    }
}
