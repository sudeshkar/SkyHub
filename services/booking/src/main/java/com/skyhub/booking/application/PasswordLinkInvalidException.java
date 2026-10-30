package com.skyhub.booking.application;

/** The emailed link is wrong, used or expired. One message for all three, so it says nothing about which. */
public class PasswordLinkInvalidException extends RuntimeException {

    public PasswordLinkInvalidException() {
        super("This link is not valid any more. Ask for a new one.");
    }
}
