package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.SeatInventory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {

    boolean existsByFlightNumberAndDepartureDate(String flightNumber, LocalDate departureDate);

    List<SeatInventory> findByFlightNumberAndDepartureDateOrderByRowNumberAscLetterAsc(
            String flightNumber, LocalDate departureDate);

    Optional<SeatInventory> findByFlightNumberAndDepartureDateAndSeatNumber(
            String flightNumber, LocalDate departureDate, String seatNumber);

    List<SeatInventory> findByHoldId(String holdId);

    /**
     * Frees every hold whose time is up, in one statement. The version is bumped by hand so this cannot
     * silently overwrite a seat that someone is changing at the same moment (optimistic locking).
     */
    @Modifying
    @Query(value = "UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL, version = version + 1 "
            + "WHERE status = 'HELD' AND held_until <= :now", nativeQuery = true)
    int expireHolds(@Param("now") Instant now);
}
