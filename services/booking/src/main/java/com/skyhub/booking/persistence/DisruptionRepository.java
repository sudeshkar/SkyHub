package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Disruption;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisruptionRepository extends JpaRepository<Disruption, Long> {

    Optional<Disruption> findByFlightNumberAndDepartureDate(String flightNumber, LocalDate departureDate);

    /** Disruptions of flights that have not left more than a day ago. Served by the unique (flight, date) index's date scan. */
    List<Disruption> findByDepartureDateGreaterThanEqual(LocalDate from);
}
