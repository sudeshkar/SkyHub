package com.skyhub.booking.application;

/** Wrong username, wrong password or a locked account. One message for all three, on purpose (ADR-0017). */
public class StaffLoginFailedException extends RuntimeException {

    public StaffLoginFailedException() {
        super("Wrong username or password");
    }
}
