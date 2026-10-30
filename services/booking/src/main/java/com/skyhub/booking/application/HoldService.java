package com.skyhub.booking.application;

import com.skyhub.booking.application.HoldMetrics.Outcome;
import com.skyhub.booking.domain.HoldIdGenerator;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Holds seats while a traveller completes a booking (ADR-0012): all requested seats or none. */
@Service
public class HoldService {

    static final int MAX_SEATS = 36; // 9 passengers x 4 flights

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);
    private static final Comparator<HeldSeat> CANONICAL_ORDER = Comparator
            .comparing(HeldSeat::flightNumber)
            .thenComparing(HeldSeat::departureDate)
            .thenComparing(HeldSeat::seatNumber);

    private final SeatInventoryRepository seats;
    private final SeatInventoryInitializer inventory;
    private final SeatMapCache seatMaps;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final HoldMetrics metrics;
    private final HoldIdGenerator holdIds;
    private final Duration timeToLive;
    private final FlightSalesGuard guard;

    public HoldService(SeatInventoryRepository seats, SeatInventoryInitializer inventory, SeatMapCache seatMaps,
                       Clock clock, TransactionOperations transactions, HoldMetrics metrics, HoldIdGenerator holdIds,
                       @Value("${skyhub.booking.hold-ttl:PT10M}") Duration timeToLive, FlightSalesGuard guard) {
        this.guard = guard;
        this.seats = seats;
        this.inventory = inventory;
        this.seatMaps = seatMaps;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
        this.holdIds = holdIds;
        this.timeToLive = timeToLive;
    }

    public HoldView createHold(CreateHoldCommand command) {
        List<HeldSeat> items = validated(command);
        guard.requireSellable(flights(items).stream().map(f -> new PnrSegment(f.flightNumber(), f.departureDate())).toList());   // nothing is locked on a flight that cannot be bought (ADR-0025)
        // Outside the hold's transaction on purpose: see SeatInventoryInitializer.
        flights(items).forEach(f -> inventory.ensureInventory(f.flightNumber(), f.departureDate()));

        String holdId = holdIds.next();
        Instant expiresAt = clock.instant().plus(timeToLive);
        try {
            transactions.executeWithoutResult(status -> holdAll(items, holdId, expiresAt));
        } catch (SeatUnavailableException | OptimisticLockingFailureException e) {
            metrics.record(Outcome.CONFLICT);
            throw e;
        } finally {
            invalidate(items);
        }
        metrics.record(Outcome.CREATED);
        log.atInfo().addKeyValue("hold.seats", items.size()).log("Seats held");
        return new HoldView(holdId, expiresAt, items);
    }

    /** Safe to repeat, and an unknown or expired hold is not an error. */
    public void releaseHold(String holdId) {
        List<HeldSeat> released = transactions.execute(status -> {
            List<SeatInventory> held = seats.findByHoldId(holdId);
            List<HeldSeat> items = held.stream()
                    .map(s -> new HeldSeat(s.getFlightNumber(), s.getDepartureDate(), s.getSeatNumber()))
                    .toList();
            held.forEach(seat -> seat.releaseHold(holdId));
            seats.flush();
            return items;
        });
        if (released != null && !released.isEmpty()) {
            metrics.record(Outcome.RELEASED);
            invalidate(released);
        }
    }

    private void holdAll(List<HeldSeat> items, String holdId, Instant expiresAt) {
        // Look every seat up before changing any, so a bad request fails without half-holding.
        List<SeatInventory> rows = items.stream().map(this::find).toList();
        rows.forEach(SeatInventory::requireAvailable);
        rows.forEach(seat -> seat.hold(holdId, expiresAt));
        // Flush now so a concurrent hold of the same seat fails here, as an optimistic-lock conflict.
        seats.flush();
    }

    private SeatInventory find(HeldSeat item) {
        return seats.findByFlightNumberAndDepartureDateAndSeatNumber(item.flightNumber(), item.departureDate(), item.seatNumber())
                .orElseThrow(() -> new InvalidBookingException(
                        "Seat " + item.seatNumber() + " does not exist on " + item.flightNumber()));
    }

    private static List<HeldSeat> validated(CreateHoldCommand command) {
        List<HeldSeat> items = command.items();
        if (items.isEmpty() || items.size() > MAX_SEATS) {
            throw new InvalidBookingException("A hold needs between 1 and " + MAX_SEATS + " seats");
        }
        Set<HeldSeat> seen = new HashSet<>();
        for (HeldSeat item : items) {
            if (!seen.add(item)) {
                throw new InvalidBookingException("Seat " + item.seatNumber() + " on " + item.flightNumber() + " is requested twice");
            }
        }
        return items.stream().sorted(CANONICAL_ORDER).toList();
    }

    private record FlightDate(String flightNumber, LocalDate departureDate) {
    }

    private static Set<FlightDate> flights(List<HeldSeat> items) {
        Set<FlightDate> flights = new HashSet<>();
        items.forEach(i -> flights.add(new FlightDate(i.flightNumber(), i.departureDate())));
        return flights;
    }

    private void invalidate(List<HeldSeat> items) {
        flights(items).forEach(f -> seatMaps.invalidate(f.flightNumber(), f.departureDate()));
    }
}
