package com.skyhub.booking.application;

import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.InvalidAccountException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.TravellerAccount;
import com.skyhub.booking.persistence.TravellerAccountRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Traveller accounts (ADR-0033). There is no "register with a password": a person asks for a link, and choosing a password
 * with that link is what proves the address is theirs. The same path resets a forgotten password. Nothing here says whether an
 * address already has an account, and sign-in never says which of "no such account", "wrong password" or "locked" happened.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 100;

    private enum Outcome { SUCCESS, FAILED, LOCKED }

    private record Attempt(Outcome outcome, TravellerAccount account) {
    }

    private final TravellerAccountRepository accounts;
    private final EmailIndex emailIndex;
    private final PasswordHasher passwords;
    private final AccountTokenIssuer tokens;
    private final AccountEvents events;
    private final Clock clock;
    private final AccountMetrics metrics;
    private final TransactionOperations tx;
    private final Supplier<String> newToken;
    /** What an unknown address is checked against, so it costs the same time as a real one. */
    private final String decoyHash;

    @Autowired
    public AccountService(TravellerAccountRepository accounts, EmailIndex emailIndex, PasswordHasher passwords,
                          AccountTokenIssuer tokens, AccountEvents events, Clock clock, AccountMetrics metrics,
                          TransactionOperations tx) {
        this(accounts, emailIndex, passwords, tokens, events, clock, metrics, tx, AccountService::randomToken);
    }

    AccountService(TravellerAccountRepository accounts, EmailIndex emailIndex, PasswordHasher passwords,
                   AccountTokenIssuer tokens, AccountEvents events, Clock clock, AccountMetrics metrics,
                   TransactionOperations tx, Supplier<String> newToken) {
        this.accounts = accounts;
        this.emailIndex = emailIndex;
        this.passwords = passwords;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
        this.tx = tx;
        this.newToken = newToken;
        this.decoyHash = passwords.hash("a password nobody has");
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Sends a link, if the address is valid and one was not sent just now. Always returns normally for a valid address. */
    public void requestPasswordLink(String rawEmail) {
        ContactEmail email = parse(rawEmail);
        String index = emailIndex.of(email.value());
        try {
            tx.executeWithoutResult(status -> issueLink(email, index));
        } catch (DataIntegrityViolationException createdMeanwhile) {
            // A parallel request made the account first and is sending the link; sending two would only be noise.
            metrics.link("throttled");
        }
    }

    private void issueLink(ContactEmail email, String index) {
        Instant now = clock.instant();
        TravellerAccount account = accounts.findForUpdateByEmailIndex(index)
                .orElseGet(() -> TravellerAccount.unverified(email, index, now));
        if (!account.mayBeSentLinkAt(now)) {
            metrics.link("throttled");
            return;
        }
        String token = newToken.get();
        PasswordLinkPurpose purpose = account.hasPassword() ? PasswordLinkPurpose.RESET : PasswordLinkPurpose.SIGN_UP;
        account.issueLink(Sha256.hex(token), now);
        account = accounts.saveAndFlush(account);   // flushing here surfaces a duplicate address inside this method
        events.passwordLinkRequested(account.getId(), email, token, purpose, account.getLinkExpiresAt());
        metrics.link("sent");
        log.atInfo().addKeyValue("account.id", account.getId()).addKeyValue("account.link_purpose", purpose)
                .log("Password link issued");
    }

    /** Spends the link: sets the password and confirms the address. */
    public void setPassword(String token, String password) {
        checkPassword(password);
        String hash = Sha256.hex(token == null ? "" : token.strip());
        tx.executeWithoutResult(status -> {
            Instant now = clock.instant();
            TravellerAccount account = accounts.findForUpdateByLinkTokenHash(hash).orElse(null);
            if (account == null || !account.linkIsValidAt(now)) {
                metrics.password("invalid_link");
                throw new PasswordLinkInvalidException();
            }
            account.setPassword(passwords.hash(password), now);
            accounts.save(account);
            metrics.password("set");
            log.atInfo().addKeyValue("account.id", account.getId()).log("Account password set");
        });
    }

    public AccountSession login(String rawEmail, String password) {
        String index = emailIndex.of(rawEmail == null ? "" : rawEmail);
        Attempt attempt = tx.execute(status -> attempt(index, password == null ? "" : password));
        switch (attempt.outcome()) {
            case SUCCESS -> {
                metrics.login("success");
                TravellerAccount account = attempt.account();
                log.atInfo().addKeyValue("account.id", account.getId()).log("Traveller signed in");
                return new AccountSession(tokens.issue(account.getId()), account.getEmail().value());
            }
            case LOCKED -> {
                metrics.login("locked");
                log.atWarn().addKeyValue("account.id", attempt.account().getId()).log("Traveller sign-in refused: account locked");
            }
            default -> {
                metrics.login("failed");
                log.atWarn().log("Traveller sign-in failed");
            }
        }
        throw new AccountLoginFailedException();
    }

    private Attempt attempt(String index, String password) {
        Instant now = clock.instant();
        TravellerAccount account = accounts.findForUpdateByEmailIndex(index).orElse(null);
        if (account == null || !account.hasPassword()) {
            passwords.matches(password, decoyHash);
            return new Attempt(Outcome.FAILED, account);
        }
        boolean correct = passwords.matches(password, account.getPasswordHash());   // checked even if locked: same cost
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

    /** The account behind a token. A token for an account that no longer exists is refused like a failed sign-in. */
    public String emailOf(long accountId) {
        return accounts.findById(accountId).orElseThrow(AccountLoginFailedException::new).getEmail().value();
    }

    private static ContactEmail parse(String rawEmail) {
        try {
            return new ContactEmail(rawEmail);
        } catch (InvalidBookingException notAnAddress) {
            throw new InvalidAccountException("Enter a valid email address");
        }
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new InvalidAccountException(
                    "A password needs " + MIN_PASSWORD_LENGTH + " to " + MAX_PASSWORD_LENGTH + " characters");
        }
    }
}
