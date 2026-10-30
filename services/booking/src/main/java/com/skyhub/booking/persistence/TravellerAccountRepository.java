package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.TravellerAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TravellerAccountRepository extends JpaRepository<TravellerAccount, Long> {

    /** Locks the row, so parallel requests for one address (links, guesses) are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TravellerAccount> findForUpdateByEmailIndex(String emailIndex);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TravellerAccount> findForUpdateByLinkTokenHash(String linkTokenHash);
}
