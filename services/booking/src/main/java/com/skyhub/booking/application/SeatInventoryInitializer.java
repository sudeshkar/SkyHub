package com.skyhub.booking.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatLayout;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creates a flight's seat rows on first use, and only for flights that really operate.
 *
 * <p>Double-checked creation, done safely: a bounded in-memory set remembers flight dates that already
 * have rows, so the hot path costs no database query. On a miss, Caffeine computes the key once while
 * concurrent callers for the same key wait (other keys are not blocked); inside, we check the
 * database, then the timetable, then insert. Across instances the unique constraint is the final guard.
 *
 * <p>Must be called outside any other transaction, so a booking never holds two pooled connections.
 */
@Component
public class SeatInventoryInitializer {

    private final SeatInventoryRepository seats;
    private final SeatLayout layout;
    private final FlightCatalog flights;
    private final TransactionOperations transactions;
    private final Cache<String, Boolean> initialized = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofDays(1))
            .recordStats()
            .build();

    public SeatInventoryInitializer(SeatInventoryRepository seats, SeatLayout layout, FlightCatalog flights,
                                    TransactionOperations transactions, MeterRegistry registry) {
        this.seats = seats;
        this.layout = layout;
        this.flights = flights;
        this.transactions = transactions;
        CaffeineCacheMetrics.monitor(registry, initialized, "seatInventory");
    }

    /**
     * @throws FlightNotFoundException if the flight does not operate on that date (nothing is created)
     * @throws FlightCatalogUnavailableException if the timetable cannot be checked right now
     */
    public void ensureInventory(String flightNumber, LocalDate departureDate) {
        initialized.get(flightNumber + "@" + departureDate, key -> createIfMissing(flightNumber, departureDate));
    }

    private Boolean createIfMissing(String flightNumber, LocalDate departureDate) {
        // Timetable first: its answers are cached, so floods of made-up flights never reach the database.
        boolean operates;
        try {
            operates = flights.operates(flightNumber, departureDate);
        } catch (FlightCatalogUnavailableException e) {
            // Existing rows were verified when they were created, so they stay usable while the timetable is down.
            if (seats.existsByFlightNumberAndDepartureDate(flightNumber, departureDate)) {
                return true;
            }
            throw e;
        }
        if (!operates) {
            throw new FlightNotFoundException(flightNumber + " does not operate on " + departureDate);
        }
        if (seats.existsByFlightNumberAndDepartureDate(flightNumber, departureDate)) {
            return true;
        }
        try {
            transactions.executeWithoutResult(status -> seats.saveAllAndFlush(layout.seats().stream()
                    .map(spec -> SeatInventory.available(flightNumber, departureDate, spec))
                    .toList()));
        } catch (DataIntegrityViolationException alreadyCreated) {
            // Another instance created the same rows first; the unique constraint kept them single.
        }
        return true;
    }
}
