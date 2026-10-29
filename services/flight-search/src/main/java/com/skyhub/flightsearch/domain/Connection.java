package com.skyhub.flightsearch.domain;

import java.time.Duration;

/** The gap between two legs, possibly including a ground transfer to another hub. */
public record Connection(
        String arrivalAirport,
        String departureAirport,
        Duration layover,
        Duration transferTime) {

    public boolean requiresAirportTransfer() {
        return !arrivalAirport.equals(departureAirport);
    }
}
