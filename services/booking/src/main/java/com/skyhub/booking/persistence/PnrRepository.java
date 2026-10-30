package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Pnr;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PnrRepository extends JpaRepository<Pnr, Long> {

    Optional<Pnr> findByLocator(String locator);

    Optional<Pnr> findByIdempotencyKeyHash(String idempotencyKeyHash);

    /** Confirmed (not cancelled) bookings that have a seat on this flight and date. */
    @Query("select distinct p from Pnr p join p.segments s "
            + "where p.status = com.skyhub.booking.domain.PnrStatus.CONFIRMED "
            + "and s.flightNumber = :flightNumber and s.departureDate = :departureDate")
    List<Pnr> findActiveOnFlight(@Param("flightNumber") String flightNumber, @Param("departureDate") LocalDate departureDate);
}
