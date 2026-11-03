package com.skyhub.booking.domain;

/** No such saved name on this account: it does not exist, or it is another account's, and the answer does not say which (ADR-0078). */
public class SavedPassengerNotFoundException extends RuntimeException {

    public SavedPassengerNotFoundException(String message) {
        super(message);
    }
}
