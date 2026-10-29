package com.skyhub.booking.domain;

/** The airline has cancelled this flight, so it cannot be checked in. */
public class FlightCancelledException extends RuntimeException {

    public FlightCancelledException(String message) {
        super(message);
    }
}
