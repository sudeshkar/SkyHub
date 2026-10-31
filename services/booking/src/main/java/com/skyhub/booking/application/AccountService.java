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

    private record Attempt(PasswordGate.Outcome outcome, TravellerAccount account) {
    }

    private final TravellerAccountRepository accounts;
    private final EmailIndex emailIndex;
    private final PasswordHasher passwords;
    private final PasswordGate gate;
    private final AccountTokenIssuer tokens;
    private final AccountEvents events;
    private final Clock clock;
    private final AccountMetrics metrics;
    private final TransactionOperations tx;
    private final Supplier<String> newToken;

    @Autowired
    public AccountService(TravellerAccountRepository accounts, EmailIndex emailIndex, PasswordHasher passwords, PasswordGate gate,
                          AccountTokenIssuer tokens, AccountEvents events, Clock clock, AccountMetrics metrics,
                          TransactionOperations tx) {
        this(accounts, emailIndex, passwords, gate, tokens, events, clock, metrics, tx, AccountService::randomToken);
    }

    AccountService(TravellerAccountRepository accounts, EmailIndex emailIndex, PasswordHasher passwords, PasswordGate gate,
                   AccountTokenIssuer tokens, AccountEvents events, Clock clock, AccountMetrics metrics,
                   TransactionOperations tx, Supplier<String> newToken) {
        this.accounts = accounts;
        this.emailIndex = emailIndex;
        this.passwords = passwords;
        this.gate = gate;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
        this.tx = tx;
        this.newToken = newToken;
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
                return new AccountSession(tokens.issue(account.getId(), account.getSessionEpoch()), account.getEmail().value());
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

    /** The gate decides (ADR-0045), the same way for sign-in and for changing the address (ADR-0039), so neither is a way round the lock. */
    private Attempt attempt(String index, String password) {
        TravellerAccount account = accounts.findForUpdateByEmailIndex(index).orElse(null);
        return new Attempt(gate.check(account, password, clock.instant()), account);
    }

    /**
     * Asks to move the account to another address (ADR-0039): the current password, then a link to the NEW address. Nothing changes
     * until someone who can read that mailbox uses the link while signed in. Nothing here says whether the new address is taken.
     */
    public void requestEmailChange(long accountId, String rawNewEmail, String password) {
        ContactEmail newEmail = parse(rawNewEmail);
        String newIndex = emailIndex.of(newEmail.value());
        String given = password == null ? "" : password;
        // The outcome is returned, not thrown, from the transaction: a thrown refusal would roll back the failed attempt it must keep.
        EmailChange outcome = tx.execute(status -> startEmailChange(accountId, newEmail, newIndex, given));
        switch (outcome) {
            case PASSWORD_REFUSED -> {
                metrics.emailChange("password_refused");
                log.atWarn().addKeyValue("account.id", accountId).log("Address change refused: password not accepted");
                throw new PasswordNotAcceptedException();
            }
            case SAME_ADDRESS -> throw new InvalidAccountException("That is already your address");
            case TAKEN -> {
                metrics.emailChange("taken");
                log.atInfo().addKeyValue("account.id", accountId).log("Address change not started: the new address has an account");
            }
            case THROTTLED -> metrics.emailChange("throttled");
            case REQUESTED -> {
                metrics.emailChange("requested");
                log.atInfo().addKeyValue("account.id", accountId).log("Address change link issued");
            }
        }
    }

    private enum EmailChange { PASSWORD_REFUSED, SAME_ADDRESS, TAKEN, THROTTLED, REQUESTED }

    private EmailChange startEmailChange(long accountId, ContactEmail newEmail, String newIndex, String password) {
        Instant now = clock.instant();
        TravellerAccount account = accounts.findForUpdateById(accountId).orElseThrow(AccountLoginFailedException::new);
        if (gate.check(account, password, now) != PasswordGate.Outcome.SUCCESS) {
            accounts.saveAndFlush(account);   // keeps the failed attempt, so asking here cannot be used to guess the password
            return EmailChange.PASSWORD_REFUSED;
        }
        accounts.saveAndFlush(account);   // the successful check clears earlier failures
        if (account.hasEmailIndex(newIndex)) {
            return EmailChange.SAME_ADDRESS;
        }
        if (accounts.existsByEmailIndex(newIndex)) {
            return EmailChange.TAKEN;
        }
        if (!account.mayBeAskedToChangeEmailAt(now)) {
            return EmailChange.THROTTLED;
        }
        String token = newToken.get();
        account.requestEmailChange(newEmail, newIndex, Sha256.hex(token), now);
        accounts.saveAndFlush(account);
        events.emailChangeRequested(accountId, newEmail, token, account.getEmailChangeExpiresAt());
        return EmailChange.REQUESTED;
    }

    /** Spends the link sent to the new address, for the signed-in account it was sent for. A bad link is the same 400 as an account link. */
    public void confirmEmailChange(long accountId, String token) {
        String hash = Sha256.hex(token == null ? "" : token.strip());
        boolean confirmed;
        try {
            confirmed = Boolean.TRUE.equals(tx.execute(status -> {
                Instant now = clock.instant();
                TravellerAccount account = accounts.findForUpdateById(accountId).orElseThrow(AccountLoginFailedException::new);
                if (!account.emailChangeIsValidFor(hash, now)) {
                    return false;
                }
                ContactEmail oldEmail = account.getEmail();
                account.confirmEmailChange(now);
                accounts.saveAndFlush(account);   // flushing here surfaces an address another account took in the meantime
                // Same transaction as the change, after it is known to hold: a notice for the old address exists exactly when a change does.
                events.emailChanged(accountId, oldEmail, account.getEmail());
                return true;
            }));
        } catch (DataIntegrityViolationException addressTakenMeanwhile) {
            confirmed = false;
        }
        if (!confirmed) {
            metrics.emailChange("invalid_link");
            throw new PasswordLinkInvalidException();
        }
        metrics.emailChange("confirmed");
        log.atInfo().addKeyValue("account.id", accountId).log("Account moved to a new address");
    }

    /**
     * Ends every open session of the account, the caller's included (ADR-0047). It needs no password: it can only make the account
     * harder to use, never easier to take. The password, the address and any lock are untouched.
     */
    public void signOutEverywhere(long accountId) {
        tx.executeWithoutResult(status -> {
            TravellerAccount account = accounts.findForUpdateById(accountId).orElseThrow(AccountLoginFailedException::new);
            account.endAllSessions();
            accounts.saveAndFlush(account);
        });
        metrics.session("signed_out_everywhere");
        log.atInfo().addKeyValue("account.id", accountId).log("All sessions of an account ended at its owner's request");
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
