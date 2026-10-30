package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Rebooking;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RebookingRepository extends JpaRepository<Rebooking, Long> {

    List<Rebooking> findByLocator(String locator);

    /** Served by ix_rebooking_from_flight; stops at the first row. */
    boolean existsByFromFlightAndFromDate(String fromFlight, java.time.LocalDate fromDate);
}
