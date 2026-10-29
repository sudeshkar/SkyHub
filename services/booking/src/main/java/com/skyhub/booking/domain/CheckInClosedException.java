package com.skyhub.booking.domain;

/** Check-in is not open yet, or has closed. The message says which, and when. */
public class CheckInClosedException extends RuntimeException {

    public CheckInClosedException(String message) {
        super(message);
    }
}
