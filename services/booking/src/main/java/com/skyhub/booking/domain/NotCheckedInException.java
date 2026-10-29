package com.skyhub.booking.domain;

/** The flight of this booking has no boarding passes yet. */
public class NotCheckedInException extends RuntimeException {

    public NotCheckedInException(String message) {
        super(message);
    }
}
