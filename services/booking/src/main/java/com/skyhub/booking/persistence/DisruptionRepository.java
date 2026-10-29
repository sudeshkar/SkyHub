package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Disruption;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisruptionRepository extends JpaRepository<Disruption, Long> {

    Optional<Disruption> findByFlightNumberAndDepartureDate(String flightNumber, LocalDate departureDate);
}
