package com.skyhub.booking.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The timer for {@link RefundRetrier}, apart from it so tests can sweep on demand and switch the timer off. */
@Component
@ConditionalOnProperty(name = "skyhub.booking.refund-retry.enabled", havingValue = "true", matchIfMissing = true)
public class RefundRetrySchedule {

    private static final Logger log = LoggerFactory.getLogger(RefundRetrySchedule.class);

    private final RefundRetrier retrier;

    public RefundRetrySchedule(RefundRetrier retrier) {
        this.retrier = retrier;
    }

    @Scheduled(fixedDelayString = "${skyhub.booking.refund-retry.interval:PT1M}", initialDelayString = "PT45S")
    public void run() {
        try {
            retrier.sweep();
        } catch (RuntimeException e) {
            log.warn("Refund retry sweep failed; will retry", e);
        }
    }
}
