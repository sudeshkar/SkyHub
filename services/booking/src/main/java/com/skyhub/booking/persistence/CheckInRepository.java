package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.CheckIn;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {

    List<CheckIn> findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(String locator, int segmentIndex);

    boolean existsByLocatorAndSegmentIndex(String locator, int segmentIndex);

    /** Whether this one passenger is checked in on the flight (ADR-0058): the seat of a checked-in passenger is frozen. */
    boolean existsByLocatorAndSegmentIndexAndPassengerIndex(String locator, int segmentIndex, int passengerIndex);

    /** Removes the check-in of the named passengers on one flight (ADR-0058). @return how many rows went */
    long deleteByLocatorAndSegmentIndexAndPassengerIndexIn(String locator, int segmentIndex, java.util.Collection<Integer> passengerIndexes);
}
