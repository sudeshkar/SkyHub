package com.skyhub.flightsearch.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * An airport where passengers may connect between flights.
 *
 * @param minimumConnectionTime shortest layover allowed when arriving and departing at this hub
 */
public record Hub(String code, String name, boolean active, Duration minimumConnectionTime) {

    public Hub {
        AirportCodes.requireValid(code);
        Objects.requireNonNull(name, "name");
        AirportCodes.requirePositive(minimumConnectionTime, "Minimum connection time of " + code);
    }
}
