package com.skyhub.booking.application;

import com.skyhub.booking.domain.InvalidStaffAccountException;
import com.skyhub.booking.domain.StaffAccount;
import com.skyhub.booking.domain.StaffRole;
import com.skyhub.booking.persistence.StaffAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Staff login and accounts (ADR-0017). Who may create accounts is the security layer's concern; this class
 * only does the work. Login never says which of "no such user", "wrong password" or "locked" happened.
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);
    private static final int MIN_PASSWORD_LENGTH = 12;

    private enum Outcome { SUCCESS, FAILED, LOCKED }

    private record Attempt(Outcome outcome, StaffAccount account) {
    }

    private final StaffAccountRepository accounts;
    private final PasswordHasher passwords;
    private final StaffTokenIssuer tokens;
    private final Clock clock;
    private final StaffMetrics metrics;
    private final TransactionOperations tx;
    /** What an unknown username is checked against, so it costs the same time as a real one. */
    private final String decoyHash;

    public StaffService(StaffAccountRepository accounts, PasswordHasher passwords, StaffTokenIssuer tokens, Clock clock,
                        StaffMetrics metrics, TransactionOperations tx) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
        this.metrics = metrics;
        this.tx = tx;
        this.decoyHash = passwords.hash("a password nobody has");
    }

    public StaffSession login(String username, String password) {
        String name = normalised(username);
        Attempt attempt = tx.execute(status -> attempt(name, password == null ? "" : password));
        switch (attempt.outcome()) {
            case SUCCESS -> {
                metrics.login("success");
                StaffAccount account = attempt.account();
                log.atInfo().addKeyValue("staff.username", account.getUsername()).log("Staff logged in");
                return new StaffSession(tokens.issue(account.getUsername(), account.getRole()),
                        account.getUsername(), account.getRole());
            }
            case LOCKED -> {
                metrics.login("locked");
                log.atWarn().addKeyValue("staff.username", name).log("Staff login refused: account locked");
            }
            default -> {
                metrics.login("failed");
                log.atWarn().addKeyValue("staff.username", name).log("Staff login failed");
            }
        }
        throw new StaffLoginFailedException();
    }

    /** One decision per attempt, in one transaction holding the account row, so parallel guesses queue up. */
    private Attempt attempt(String name, String password) {
        Instant now = clock.instant();
        StaffAccount account = accounts.findForUpdateByUsername(name).orElse(null);
        if (account == null) {
            passwords.matches(password, decoyHash);
            return new Attempt(Outcome.FAILED, null);
        }
        boolean correct = passwords.matches(password, account.getPasswordHash()); // checked even if locked: same cost
        if (account.isLockedAt(now)) {
            return new Attempt(Outcome.LOCKED, account);
        }
        if (correct) {
            account.recordSuccessfulLogin();
            accounts.save(account);
            return new Attempt(Outcome.SUCCESS, account);
        }
        account.recordFailedLogin(now);
        accounts.save(account);
        return new Attempt(Outcome.FAILED, account);
    }

    public StaffAccountView create(String createdBy, CreateStaffCommand command) {
        String name = normalised(command.username());
        checkPassword(command.password());
        if (accounts.existsByUsername(name)) {
            throw new StaffUsernameTakenException();
        }
        StaffAccount account = StaffAccount.create(name, passwords.hash(command.password()), command.role(), clock.instant());
        accounts.save(account);
        log.atInfo()
                .addKeyValue("staff.created", account.getUsername())
                .addKeyValue("staff.role", account.getRole())
                .addKeyValue("staff.created_by", createdBy)
                .log("Staff account created");
        return new StaffAccountView(account.getUsername(), account.getRole());
    }

    /** Creates the first administrator when there are no accounts yet and both settings are given. */
    public boolean ensureFirstAdmin(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank() || accounts.count() > 0) {
            return false;
        }
        create("bootstrap", new CreateStaffCommand(username, password, StaffRole.ADMIN));
        return true;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidStaffAccountException("A password needs at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private static String normalised(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
