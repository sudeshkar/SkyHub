package com.skyhub.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A member of staff who can log in (ADR-0017). Holds a password <em>hash</em>, never the password. Five wrong
 * passwords lock the account for fifteen minutes; a correct login clears the count.
 */
@Entity
@Table(name = "staff_account")
public class StaffAccount {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final Pattern USERNAME = Pattern.compile("[a-z][a-z0-9._-]{2,39}");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40, unique = true)
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StaffRole role;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

    @Column(nullable = false)
    private Instant createdAt;

    protected StaffAccount() {
    }

    public static StaffAccount create(String username, String passwordHash, StaffRole role, Instant now) {
        String name = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new InvalidStaffAccountException(
                    "A username starts with a letter and has 3 to 40 lower-case letters, digits, dots, dashes or underscores");
        }
        StaffAccount account = new StaffAccount();
        account.username = name;
        account.passwordHash = passwordHash;
        account.role = role;
        account.createdAt = now;
        return account;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void recordFailedLogin(Instant now) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) { // an old lock has run out: start counting afresh
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

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public StaffRole getRole() {
        return role;
    }

    /** A hash is still a secret worth keeping out of logs. */
    @Override
    public String toString() {
        return "StaffAccount[username=" + username + ", role=" + role + "]";
    }
}
