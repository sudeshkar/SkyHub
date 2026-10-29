package com.skyhub.booking.domain;

/** The flight is checked in, so its seats are frozen. */
public class AlreadyCheckedInException extends RuntimeException {

    public AlreadyCheckedInException(String message) {
        super(message);
    }
}
