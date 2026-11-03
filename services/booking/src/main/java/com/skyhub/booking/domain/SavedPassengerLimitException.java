package com.skyhub.booking.domain;

/** The account already keeps as many names as it may (ADR-0078). */
public class SavedPassengerLimitException extends RuntimeException {

    public SavedPassengerLimitException(String message) {
        super(message);
    }
}
