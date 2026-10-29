package com.skyhub.flightsearch.domain;

import java.time.Duration;

/** Ground transfer between two hubs in the same city. Applies in both directions. */
public record AirportTransfer(String from, String to, Duration duration) {

    public AirportTransfer {
        AirportCodes.requireValid(from);
        AirportCodes.requireValid(to);
        if (from.equals(to)) {
            throw new IllegalArgumentException("Transfer must connect two different airports: " + from);
        }
        AirportCodes.requirePositive(duration, "Transfer time " + from + "-" + to);
    }
}
