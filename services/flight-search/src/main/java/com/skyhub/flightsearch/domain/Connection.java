package com.skyhub.flightsearch.domain;

import java.io.Serializable;
import java.time.Duration;

/** The gap between two legs, possibly including a ground transfer to another hub. */
public record Connection(
        String arrivalAirport,
        String departureAirport,
        Duration layover,
        Duration transferTime) implements Serializable {

    public boolean requiresAirportTransfer() {
        return !arrivalAirport.equals(departureAirport);
    }
}
