package com.skyhub.booking.application;

import com.skyhub.booking.application.HoldMetrics.Outcome;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Frees holds whose time is up. One SQL statement, so it is idempotent: several instances running it
 * together do no harm. Correctness never depends on it, because converting a hold checks the expiry itself.
 */
@Component
public class HoldExpirer {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirer.class);

    private final SeatInventoryRepository seats;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final HoldMetrics metrics;

    public HoldExpirer(SeatInventoryRepository seats, Clock clock, TransactionOperations transactions, HoldMetrics metrics) {
        this.seats = seats;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${skyhub.booking.hold-sweep-interval:PT30S}")
    public void expire() {
        try {
            Integer freed = transactions.execute(status -> seats.expireHolds(clock.instant()));
            if (freed != null && freed > 0) {
                metrics.record(Outcome.EXPIRED, freed);
                log.atInfo().addKeyValue("hold.seats", freed).log("Expired holds freed");
            }
        } catch (RuntimeException e) {
            // A failed sweep must not stop the schedule: the next one tries again.
            log.warn("Hold cleanup failed; will retry", e);
        }
    }
}
