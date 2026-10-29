package com.skyhub.booking.domain;

import java.time.Duration;
import java.time.Instant;

/** When check-in is possible, as distances before departure: it opens {@code opensBefore} early and closes {@code closesBefore} early. */
public record CheckInWindow(Duration opensBefore, Duration closesBefore) {

    public CheckInWindow {
        if (closesBefore.compareTo(opensBefore) >= 0) {
            throw new IllegalArgumentException("Check-in must close after it opens");
        }
    }

    /** @throws CheckInClosedException when {@code now} is outside the window */
    public void requireOpen(Instant departure, Instant now) {
        Instant opens = departure.minus(opensBefore);
        Instant closes = departure.minus(closesBefore);
        if (now.isBefore(opens)) {
            throw new CheckInClosedException("Check-in opens at " + opens);
        }
        if (!now.isBefore(closes)) {
            throw new CheckInClosedException("Check-in is closed; it closed at " + closes);
        }
    }
}
