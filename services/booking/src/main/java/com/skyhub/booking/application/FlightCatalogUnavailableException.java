package com.skyhub.booking.application;

public class FlightCatalogUnavailableException extends RuntimeException {

    public FlightCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
