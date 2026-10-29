package com.skyhub.flightsearch.persistence;

import com.skyhub.flightsearch.domain.FlightSchedule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlightScheduleRepository extends JpaRepository<FlightSchedule, Long> {

    List<FlightSchedule> findByOrigin(String origin);

    List<FlightSchedule> findByDestination(String destination);
}
