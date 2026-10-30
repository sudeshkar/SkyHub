package com.skyhub.booking.application.rebooking;

/** A move the rules do not allow. The message says which rule and why. Maps to 409. */
public class RebookingRefusedException extends RuntimeException {

    public RebookingRefusedException(String message) {
        super(message);
    }
}
