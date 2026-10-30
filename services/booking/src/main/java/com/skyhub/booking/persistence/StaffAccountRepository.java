package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.StaffAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface StaffAccountRepository extends JpaRepository<StaffAccount, Long> {

    /** Locks the row, so two parallel guesses at one account are counted one after the other, not both as the first. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<StaffAccount> findForUpdateByUsername(String username);

    boolean existsByUsername(String username);
}
