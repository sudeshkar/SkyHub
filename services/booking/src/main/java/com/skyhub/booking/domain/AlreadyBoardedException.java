package com.skyhub.booking.domain;

/** The passenger has boarded, so they can no longer check out (ADR-0060). */
public class AlreadyBoardedException extends RuntimeException {

    public AlreadyBoardedException(String message) {
        super(message);
    }
}
