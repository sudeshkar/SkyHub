package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.SeatInventory;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {

    boolean existsByFlightNumberAndDepartureDate(String flightNumber, LocalDate departureDate);

    List<SeatInventory> findByFlightNumberAndDepartureDateOrderByRowNumberAscLetterAsc(
            String flightNumber, LocalDate departureDate);

    Optional<SeatInventory> findByFlightNumberAndDepartureDateAndSeatNumber(
            String flightNumber, LocalDate departureDate, String seatNumber);
}
