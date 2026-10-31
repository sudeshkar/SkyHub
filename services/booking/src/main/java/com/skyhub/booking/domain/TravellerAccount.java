package com.skyhub.booking.domain;

import jakarta.persistence.Column;
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
public class TravellerAccount {

    public static final Duration LINK_LIFETIME = Duration.ofHours(1);
    public static final Duration LINK_RESEND_AFTER = Duration.ofMinutes(2);
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

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

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

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
        failedAttempts = 0;
        lockedUntil = null;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void recordFailedLogin(Instant now) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) {
            lockedUntil = null;
            failedAttempts = 0;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
            failedAttempts = 0;
        }
    }

    public void recordSuccessfulLogin() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public Long getId() {
        return id;
    }

    public ContactEmail getEmail() {
        return email;
    }

    public String getEmailIndex() {
        return emailIndex;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getLinkExpiresAt() {
        return linkExpiresAt;
    }

    @Override
    public String toString() {
        return "TravellerAccount[id=" + id + ", email=" + email + "]";
    }
}
