package com.skyhub.booking.domain;

/** There is no boarding to take back: that passenger has not boarded that flight, or it was already taken back (ADR-0064). */
public class NotBoardedException extends RuntimeException {

    public NotBoardedException(String message) {
        super(message);
    }
}
