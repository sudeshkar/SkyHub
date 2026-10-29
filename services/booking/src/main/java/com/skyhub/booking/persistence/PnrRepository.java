package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Pnr;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PnrRepository extends JpaRepository<Pnr, Long> {

    Optional<Pnr> findByLocator(String locator);

    Optional<Pnr> findByIdempotencyKeyHash(String idempotencyKeyHash);
}
