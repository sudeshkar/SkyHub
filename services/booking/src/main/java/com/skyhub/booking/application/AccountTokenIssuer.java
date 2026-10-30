package com.skyhub.booking.application;

/** Port: issues the credential that carries an account's id. The JWT adapter lives in security. */
@FunctionalInterface
public interface AccountTokenIssuer {

    AccountToken issue(long accountId);
}
