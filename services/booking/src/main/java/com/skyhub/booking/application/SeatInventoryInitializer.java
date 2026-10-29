package com.skyhub.booking.application;

import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatLayout;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.LocalDate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creates a flight's seat rows on first use. Runs in its own short transaction and must be called
 * outside any other transaction, so a booking never holds two pooled connections at once.
 */
@Component
public class SeatInventoryInitializer {

    private final SeatInventoryRepository seats;
    private final SeatLayout layout;
    private final TransactionOperations transactions;

    public SeatInventoryInitializer(SeatInventoryRepository seats, SeatLayout layout,
                                    TransactionOperations transactions) {
        this.seats = seats;
        this.layout = layout;
        this.transactions = transactions;
    }

    public void ensureInventory(String flightNumber, LocalDate departureDate) {
        if (seats.existsByFlightNumberAndDepartureDate(flightNumber, departureDate)) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> seats.saveAllAndFlush(layout.seats().stream()
                    .map(spec -> SeatInventory.available(flightNumber, departureDate, spec))
                    .toList()));
        } catch (DataIntegrityViolationException alreadyCreated) {
            // A concurrent request created the same rows first; the unique constraint kept them single.
        }
    }
}
