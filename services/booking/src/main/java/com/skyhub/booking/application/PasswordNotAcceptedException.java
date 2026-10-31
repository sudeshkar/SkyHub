package com.skyhub.booking.application;

/**
 * The current password was wrong, the account has none, or it is locked (ADR-0039). One answer for all three, like sign-in. It is a
 * 403, not a 401, so a signed-in person who mistypes is not taken for someone whose session ended.
 */
public class PasswordNotAcceptedException extends RuntimeException {

    public PasswordNotAcceptedException() {
        super("The password was not accepted, or the account is temporarily locked");
    }
}
