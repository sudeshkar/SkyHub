package com.skyhub.flightsearch.domain;

public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(String message) {
        super(message);
    }
}
