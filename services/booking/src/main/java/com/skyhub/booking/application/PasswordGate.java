package com.skyhub.booking.application;

import com.skyhub.booking.domain.PasswordProtected;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The one decision about a password, for every kind of account that has one (ADR-0045): staff signing in, a traveller signing in, and
 * a traveller proving who they are before changing their address. It answers {@code SUCCESS}, {@code FAILED} or {@code LOCKED} and
 * records the result on the account, which the caller then saves. What to do with each answer (a metric, a log line, which
 * exception) is the caller's business, because it differs.
 *
 * <p>It is built so that no answer is quicker than another: an unknown account, one with no password, a locked one and a wrong
 * password each cost one comparison, so timing says nothing about which accounts exist or are locked. A locked account is refused even
 * with the right password, and a refusal while locked does not extend the lock. The decoy hash that makes an unknown account cost
 * the same is made once, here.
 */
@Component
public class PasswordGate {

    public enum Outcome { SUCCESS, FAILED, LOCKED }

    private final PasswordHasher passwords;
    /** What an unknown account, or one with no password, is checked against, so it costs the same as a real one. */
    private final String decoyHash;

    public PasswordGate(PasswordHasher passwords) {
        this.passwords = passwords;
        this.decoyHash = passwords.hash("a password nobody has");
    }

    /** @param account the account, or null when there is none; the result is recorded on it */
    public Outcome check(PasswordProtected account, String password, Instant now) {
        if (account == null || account.getPasswordHash() == null) {
            passwords.matches(password, decoyHash);   // the answer is thrown away: this exists only to cost the same time
            return Outcome.FAILED;
        }
        boolean correct = passwords.matches(password, account.getPasswordHash());   // checked even if locked: same cost
        if (account.isLockedAt(now)) {
            return Outcome.LOCKED;
        }
        if (correct) {
            account.recordSuccessfulLogin();
            return Outcome.SUCCESS;
        }
        account.recordFailedLogin(now);
        return Outcome.FAILED;
    }
}
