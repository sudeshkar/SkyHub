package com.skyhub.booking.domain;

/** The request for an account action is not acceptable (a bad address, a weak password). The message is safe to show. */
public class InvalidAccountException extends RuntimeException {

    public InvalidAccountException(String message) {
        super(message);
    }
}
