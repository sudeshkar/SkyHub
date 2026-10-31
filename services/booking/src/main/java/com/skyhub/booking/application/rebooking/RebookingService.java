package com.skyhub.booking.application.rebooking;

import com.skyhub.booking.application.BookingEvents;
import com.skyhub.booking.application.FlightCatalog;
import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.application.SeatInventoryInitializer;
import com.skyhub.booking.application.SeatMapCache;
import com.skyhub.booking.domain.Cabin;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.Rebooking;
import com.skyhub.booking.domain.SeatAllocator;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatStatus;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.RebookingRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Moves one flight of a booking onto another, on staff's say-so (ADR-0023). The service never trusts whoever proposed the
 * move (a person, or the disruption agent): every rule runs again here, and the seats are chosen here.
 *
 * <p>One transaction covers the booking, the seats on both flights, the audit row and the outbox event, so a move either
 * happens completely or not at all. New seats are booked before old ones are released, and all seats for the whole party
 * are chosen before any is touched, so a party that does not fit changes nothing.
 *
 * <p>Cost: one read of the target flight's seats, partitioned by cabin in a single pass, then the allocator's sort per cabin
 * (O(n log n)); the rules are O(1) each over a context loaded once.
 */
@Service
public class RebookingService {

    private static final Logger log = LoggerFactory.getLogger(RebookingService.class);

    private final PnrRepository pnrs;
    private final SeatInventoryRepository seats;
    private final CheckInRepository checkIns;
    private final DisruptionRepository disruptions;
    private final FlightCatalog catalog;
    private final SeatInventoryInitializer inventory;
    private final SeatAllocator allocator;
    private final List<RebookingRule> rules;
    private final RebookingRepository audit;
    private final SeatMapCache seatMaps;
    private final BookingEvents events;
    private final RebookingMetrics metrics;
    private final Clock clock;
    private final TransactionOperations transactions;

    public RebookingService(PnrRepository pnrs, SeatInventoryRepository seats, CheckInRepository checkIns,
                            DisruptionRepository disruptions, FlightCatalog catalog, SeatInventoryInitializer inventory,
                            SeatAllocator allocator, List<RebookingRule> rules, RebookingRepository audit,
                            SeatMapCache seatMaps, BookingEvents events, RebookingMetrics metrics, Clock clock,
                            TransactionOperations transactions) {
        this.pnrs = pnrs;
        this.seats = seats;
        this.checkIns = checkIns;
        this.disruptions = disruptions;
        this.catalog = catalog;
        this.inventory = inventory;
        this.allocator = allocator;
        this.rules = List.copyOf(rules);
        this.audit = audit;
        this.seatMaps = seatMaps;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.transactions = transactions;
    }

    public RebookingResult rebook(RebookCommand command) {
        PnrSegment target = new PnrSegment(command.flightNumber(), command.departureDate());
        RebookingResult result = null;
        try {
            // Outside the transaction on purpose: network calls, and seat inventory is created in its own transaction.
            FlightInfo targetInfo = catalog.find(target.flightNumber(), target.departureDate())
                    .orElseThrow(() -> new RebookingRefusedException(
                            target.flightNumber() + " does not operate on " + target.departureDate()));
            inventory.ensureInventory(target.flightNumber(), target.departureDate());
            result = transactions.execute(status -> move(command, target, targetInfo));
        } catch (RebookingRefusedException refused) {
            metrics.record("refused");
            throw refused;
        } finally {
            if (result != null && result.changed()) {
                seatMaps.invalidate(result.from().flightNumber(), result.from().departureDate());
                seatMaps.invalidate(target.flightNumber(), target.departureDate());
            }
        }
        metrics.record(result.changed() ? "rebooked" : "unchanged");
        if (result.changed()) {
            log.atInfo()
                    .addKeyValue("pnr.locator", command.locator())
                    .addKeyValue("rebooking.from", result.from().flightNumber() + "@" + result.from().departureDate())
                    .addKeyValue("rebooking.to", target.flightNumber() + "@" + target.departureDate())
                    .addKeyValue("rebooking.by", command.rebookedBy())
                    .log("Booking rebooked");
        }
        return result;
    }

    private RebookingResult move(RebookCommand command, PnrSegment target, FlightInfo targetInfo) {
        Pnr pnr = pnrs.findByLocator(command.locator())
                .orElseThrow(() -> new PnrNotFoundException("No booking matches this reference"));
        pnr.requireActive();
        int index = command.segmentIndex();
        if (index < 0 || index >= pnr.getSegments().size()) {
            throw new InvalidBookingException("This booking has no flight " + index);
        }
        PnrSegment from = pnr.getSegments().get(index);
        List<String> currentSeats = seatsOn(pnr, index);
        if (from.equals(target)) {
            return new RebookingResult(command.locator(), index, from, target, currentSeats, false);
        }

        FlightInfo fromInfo = catalog.find(from.flightNumber(), from.departureDate())
                .orElseThrow(() -> new RebookingRefusedException(from.flightNumber() + " is no longer in the timetable"));
        RebookingContext context = new RebookingContext(pnr, index, from, target, fromInfo, targetInfo,
                disruptions.findByFlightNumberAndDepartureDate(from.flightNumber(), from.departureDate()),
                disruptions.findByFlightNumberAndDepartureDate(target.flightNumber(), target.departureDate()),
                checkIns.existsByLocatorAndSegmentIndex(command.locator(), index),
                neighbour(pnr, index - 1), neighbour(pnr, index + 1));
        for (RebookingRule rule : rules) {
            rule.refusal(context).ifPresent(reason -> {
                throw new RebookingRefusedException(reason);
            });
        }

        List<String> newSeats = chooseSeats(pnr, index, from, target);   // all or nothing: nothing is booked yet
        for (String seat : newSeats) {
            seatOf(target, seat).book(command.locator());
        }
        seats.flush();   // a concurrent booking of the same seat fails here, as an optimistic-lock conflict
        for (String seat : currentSeats) {
            seatOf(from, seat).release(command.locator());
        }
        pnr.rebook(index, target, newSeats);
        pnrs.saveAndFlush(pnr);
        audit.save(Rebooking.of(command.locator(), index, from, target, command.rebookedBy(), clock.instant()));
        events.bookingRebooked(pnr, from, target);   // same transaction: the email exists exactly when the move does
        return new RebookingResult(command.locator(), index, from, target, newSeats, true);
    }

    /**
     * The flight of this booking at {@code position}, if there is one and the timetable still has it (ADR-0036). A flight that is no
     * longer in the timetable cannot be judged and is skipped. At most two of these per move, both answered from the cache.
     */
    private Optional<RebookingContext.Neighbour> neighbour(Pnr pnr, int position) {
        if (position < 0 || position >= pnr.getSegments().size()) {
            return Optional.empty();
        }
        PnrSegment flight = pnr.getSegments().get(position);
        return catalog.find(flight.flightNumber(), flight.departureDate()).map(info -> new RebookingContext.Neighbour(flight, info,
                disruptions.findByFlightNumberAndDepartureDate(flight.flightNumber(), flight.departureDate())));
    }

    /** One seat per passenger, each in the cabin they were sitting in, with each cabin's group kept together. */
    private List<String> chooseSeats(Pnr pnr, int index, PnrSegment from, PnrSegment target) {
        Map<Cabin, List<Integer>> passengersByCabin = new EnumMap<>(Cabin.class);
        for (int p = 0; p < pnr.getPassengers().size(); p++) {
            passengersByCabin.computeIfAbsent(seatOf(from, pnr.seatOf(p, index)).getCabin(), c -> new ArrayList<>()).add(p);
        }
        Map<Cabin, List<SeatInventory>> freeByCabin = new EnumMap<>(Cabin.class);
        for (SeatInventory seat : seats.findByFlightNumberAndDepartureDateOrderByRowNumberAscLetterAsc(
                target.flightNumber(), target.departureDate())) {
            if (seat.getStatus() == SeatStatus.AVAILABLE) {
                freeByCabin.computeIfAbsent(seat.getCabin(), c -> new ArrayList<>()).add(seat);
            }
        }
        String[] chosen = new String[pnr.getPassengers().size()];
        passengersByCabin.forEach((cabin, passengers) -> {
            List<SeatInventory> picked = allocator.allocate(freeByCabin.getOrDefault(cabin, List.of()), passengers.size());
            for (int i = 0; i < passengers.size(); i++) {
                chosen[passengers.get(i)] = picked.get(i).getSeatNumber();
            }
        });
        return List.of(chosen);
    }

    private static List<String> seatsOn(Pnr pnr, int index) {
        List<String> current = new ArrayList<>();
        for (int p = 0; p < pnr.getPassengers().size(); p++) {
            current.add(pnr.seatOf(p, index));
        }
        return current;
    }

    private SeatInventory seatOf(PnrSegment flight, String seatNumber) {
        return seats.findByFlightNumberAndDepartureDateAndSeatNumber(flight.flightNumber(), flight.departureDate(), seatNumber)
                .orElseThrow(() -> new InvalidBookingException("Seat " + seatNumber + " does not exist on " + flight.flightNumber()));
    }
}
