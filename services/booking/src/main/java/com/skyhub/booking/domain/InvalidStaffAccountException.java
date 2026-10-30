package com.skyhub.booking.domain;

/** A staff account that breaks the rules (bad username, weak password). Maps to 400. */
public class InvalidStaffAccountException extends RuntimeException {

    public InvalidStaffAccountException(String message) {
        super(message);
    }
}
