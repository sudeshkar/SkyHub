package com.skyhub.booking.application;

/** One answer for every sign-in failure, so nothing says which addresses have accounts (ADR-0033). */
public class AccountLoginFailedException extends RuntimeException {

    public AccountLoginFailedException() {
        super("Wrong email or password, or the account is temporarily locked");
    }
}
