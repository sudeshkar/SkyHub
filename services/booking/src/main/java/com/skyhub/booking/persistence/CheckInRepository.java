package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.CheckIn;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {

    List<CheckIn> findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(String locator, int segmentIndex);

    boolean existsByLocatorAndSegmentIndex(String locator, int segmentIndex);

    /** Removes the check-in of the whole party on one flight (ADR-0055). @return how many rows went */
    long deleteByLocatorAndSegmentIndex(String locator, int segmentIndex);
}
