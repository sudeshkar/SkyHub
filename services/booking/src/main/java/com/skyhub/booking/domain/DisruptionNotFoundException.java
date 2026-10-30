package com.skyhub.booking.domain;

/** There is no disruption recorded for the flight and date, so there is nothing to withdraw. */
public class DisruptionNotFoundException extends RuntimeException {

    public DisruptionNotFoundException(String message) {
        super(message);
    }
}
