package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Rebooking;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RebookingRepository extends JpaRepository<Rebooking, Long> {

    List<Rebooking> findByLocator(String locator);
}
