package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * A traveller who can sign in (ADR-0033). The account exists from the first request for a link, but has no password until
 * the person uses an emailed link, so nobody can sign up with an address that is not theirs. Holds a password
 * <em>hash</em>, never the password, and only the hash of the emailed token.
 */
@Entity
@Table(name = "traveller_account")
public class TravellerAccount implements PasswordProtected {

    public static final Duration LINK_LIFETIME = Duration.ofHours(1);
    public static final Duration LINK_RESEND_AFTER = Duration.ofMinutes(2);
    public static final int MAX_FAILED_ATTEMPTS = LoginLock.MAX_FAILED_ATTEMPTS;
    public static final Duration LOCK_DURATION = LoginLock.LOCK_DURATION;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Encrypted at rest by the converter that applies to every ContactEmail. */
    @Column(nullable = false, length = 500)
    private ContactEmail email;

    @Column(nullable = false, length = 64, unique = true)
    private String emailIndex;

    @Column(length = 100)
    private String passwordHash;

    private Instant verifiedAt;

    @Column(length = 64)
    private String linkTokenHash;

    private Instant linkIssuedAt;

    private Instant linkExpiresAt;

    /** The address this account is waiting to move to, encrypted like the current one (ADR-0039). Null when no change is pending. */
    @Column(length = 500)
    private ContactEmail pendingEmail;

    @Column(length = 64)
    private String pendingEmailIndex;

    /** Only the hash of the token emailed to the new address, so a copy of the table cannot confirm a change. */
    @Column(length = 64)
    private String changeTokenHash;

    private Instant changeIssuedAt;

    private Instant changeExpiresAt;

    /**
     * Goes up when the credentials change, which ends every session issued before (ADR-0041): an account token carries the epoch it
     * was issued under and is valid only while it is still this one.
     */
    @Column(nullable = false)
    private int sessionEpoch;

    /** The one rule for locking after wrong passwords, shared with staff accounts (ADR-0045). */
    @Embedded
    private LoginLock lock = new LoginLock();

    @Column(nullable = false)
    private Instant createdAt;

    protected TravellerAccount() {
    }

    /** A new account that cannot be signed in to until its owner chooses a password. */
    public static TravellerAccount unverified(ContactEmail email, String emailIndex, Instant now) {
        TravellerAccount account = new TravellerAccount();
        account.email = email;
        account.emailIndex = emailIndex;
        account.createdAt = now;
        return account;
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }

    /** Asking again straight away would only be used to flood someone's inbox. */
    public boolean mayBeSentLinkAt(Instant now) {
        return linkIssuedAt == null || !now.isBefore(linkIssuedAt.plus(LINK_RESEND_AFTER));
    }

    /** Replaces any earlier link: only the newest one works. */
    public void issueLink(String tokenHash, Instant now) {
        linkTokenHash = tokenHash;
        linkIssuedAt = now;
        linkExpiresAt = now.plus(LINK_LIFETIME);
    }

    public boolean linkIsValidAt(Instant now) {
        return linkTokenHash != null && linkExpiresAt != null && now.isBefore(linkExpiresAt);
    }

    /** Using the link: sets the password, confirms the address, spends the link and clears any lock. */
    public void setPassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        if (verifiedAt == null) {
            verifiedAt = now;
        }
        linkTokenHash = null;
        linkExpiresAt = null;
        lock.clear();
        endAllSessions();   // choosing a password is a change of credentials: every open session ends
    }

    @Override
    public boolean isLockedAt(Instant now) {
        return lock.isLockedAt(now);
    }

    @Override
    public void recordFailedLogin(Instant now) {
        lock.recordFailure(now);
    }

    @Override
    public void recordSuccessfulLogin() {
        lock.recordSuccess();
    }

    /** Asking again straight away would only be used to flood someone's inbox. */
    public boolean mayBeAskedToChangeEmailAt(Instant now) {
        return changeIssuedAt == null || !now.isBefore(changeIssuedAt.plus(LINK_RESEND_AFTER));
    }

    /** Keeps the new address pending until it is proved. Replaces any earlier request: only the newest link works. */
    public void requestEmailChange(ContactEmail newEmail, String newEmailIndex, String tokenHash, Instant now) {
        pendingEmail = newEmail;
        pendingEmailIndex = newEmailIndex;
        changeTokenHash = tokenHash;
        changeIssuedAt = now;
        changeExpiresAt = now.plus(LINK_LIFETIME);
    }

    public boolean emailChangeIsValidFor(String tokenHash, Instant now) {
        return changeTokenHash != null && tokenHash != null && changeTokenHash.equals(tokenHash)
                && changeExpiresAt != null && now.isBefore(changeExpiresAt);
    }

    /**
     * Moves to the pending address and spends the request. A password link that was already out is stopped too: it went to the
     * old address, and whoever still reads that mailbox must not be able to set a password on an account that has moved on.
     * The lock state is left alone.
     */
    public void confirmEmailChange(Instant now) {
        if (pendingEmail == null || pendingEmailIndex == null) {
            throw new IllegalStateException("No address change is pending");
        }
        endAllSessions();   // moving the account is a change of credentials: every open session ends
        email = pendingEmail;
        emailIndex = pendingEmailIndex;
        pendingEmail = null;
        pendingEmailIndex = null;
        changeTokenHash = null;
        changeIssuedAt = null;
        changeExpiresAt = null;
        linkTokenHash = null;
        linkExpiresAt = null;
    }

    public boolean hasEmailIndex(String index) {
        return emailIndex.equals(index);
    }

    public Long getId() {
        return id;
    }

    public ContactEmail getEmail() {
        return email;
    }

    /**
     * Every token issued before this is refused from now on (ADR-0041). Changing the password or the address does it, and the person
     * can ask for it directly (ADR-0047). It touches nothing else about the account, a lock included.
     */
    public void endAllSessions() {
        sessionEpoch++;
    }

    public int getSessionEpoch() {
        return sessionEpoch;
    }

    public String getEmailIndex() {
        return emailIndex;
    }

    @Override
    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getLinkExpiresAt() {
        return linkExpiresAt;
    }

    public Instant getEmailChangeExpiresAt() {
        return changeExpiresAt;
    }

    @Override
    public String toString() {
        return "TravellerAccount[id=" + id + ", email=" + email + "]";
    }
}
