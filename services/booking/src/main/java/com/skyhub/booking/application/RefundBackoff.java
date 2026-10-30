package com.skyhub.booking.application;

import java.time.Duration;

/**
 * How long to wait before trying a failed refund again (ADR-0030): 5 minutes, doubling, never more than 4 hours, and
 * giving up after 12 attempts (about a day and a quarter). Giving up is what makes a person look.
 */
public final class RefundBackoff {

    public static final int MAX_ATTEMPTS = 12;
    private static final Duration BASE = Duration.ofMinutes(5);
    private static final Duration CAP = Duration.ofHours(4);

    private RefundBackoff() {
    }

    /** The wait after the given number of attempts have been made (1 = the first attempt just failed). */
    public static Duration delayAfter(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1");
        }
        // Past 6 doublings the cap has been reached; stopping the shift there also avoids overflow.
        Duration delay = BASE.multipliedBy(1L << Math.min(attempts - 1, 6));
        return delay.compareTo(CAP) > 0 ? CAP : delay;
    }
}
