package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByLocator(String locator);
}
