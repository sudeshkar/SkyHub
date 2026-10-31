package com.skyhub.booking.application;

/** Port: issues the credential that carries an account's id and session epoch (ADR-0041). The JWT adapter lives in security. */
@FunctionalInterface
public interface AccountTokenIssuer {

    /** A token valid while {@code epoch} is still the account's session epoch; changing the account's credentials ends it. */
    AccountToken issue(long accountId, int epoch);

    /** Under epoch 0, which is where every account starts. */
    default AccountToken issue(long accountId) {
        return issue(accountId, 0);
    }
}
