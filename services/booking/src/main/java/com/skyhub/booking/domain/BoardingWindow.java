package com.skyhub.booking.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * When a gate may board passengers, as distances before departure (ADR-0063): boarding opens {@code opensBefore} early and closes
 * {@code closesBefore} early, so it is shut well before the doors are. Pure rule: the caller says when the flight will really leave
 * (a delay moves the window) and what the time is now.
 */
public record BoardingWindow(Duration opensBefore, Duration closesBefore) {

    public enum State { NOT_YET_OPEN, OPEN, CLOSED }

    public BoardingWindow {
        if (closesBefore.isNegative() || closesBefore.compareTo(opensBefore) >= 0) {
            throw new IllegalArgumentException("Boarding must close after it opens, and not after departure");
        }
    }

    public State stateAt(Instant departure, Instant now) {
        if (now.isBefore(departure.minus(opensBefore))) {
            return State.NOT_YET_OPEN;
        }
        return now.isBefore(departure.minus(closesBefore)) ? State.OPEN : State.CLOSED;
    }
}
