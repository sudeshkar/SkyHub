package com.skyhub.flightsearch.messaging;

/** A message on the flight-disruptions topic that cannot be acted on. It is skipped and counted, never retried. */
public class InvalidFlightEventException extends RuntimeException {

    public InvalidFlightEventException(String message) {
        super(message);
    }

    public InvalidFlightEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
