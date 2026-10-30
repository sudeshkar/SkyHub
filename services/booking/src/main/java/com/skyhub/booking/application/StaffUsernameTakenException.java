package com.skyhub.booking.application;

public class StaffUsernameTakenException extends RuntimeException {

    public StaffUsernameTakenException() {
        super("That username is taken");
    }
}
