package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.booking.application.PasswordGate.Outcome;
import com.skyhub.booking.domain.LoginLock;
import com.skyhub.booking.domain.PasswordProtected;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The one decision about a password, for every kind of account that has one (ADR-0045). */
class PasswordGateTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String DECOY = "h:a password nobody has";

    /** Records every comparison, so the cost of each path can be seen: a path that skips the hasher would be quicker to refuse. */
    private static class CountingHasher implements PasswordHasher {
        final List<String> comparedAgainst = new ArrayList<>();

        @Override
        public String hash(String password) {
            return "h:" + password;
        }

        @Override
        public boolean matches(String password, String hash) {
            comparedAgainst.add(hash);
            return hash.equals("h:" + password);
        }
    }

    /** Any kind of account: only what the gate needs. */
    private static final class Account implements PasswordProtected {
        final String hash;
        final LoginLock lock = new LoginLock();

        Account(String hash) {
            this.hash = hash;
        }

        @Override
        public String getPasswordHash() {
            return hash;
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
    }

    private final CountingHasher hasher = new CountingHasher();
    private final PasswordGate gate = new PasswordGate(hasher);

    @Test
    void should_let_the_right_password_in_and_clear_earlier_failures() {
        Account account = new Account("h:correct horse");
        gate.check(account, "wrong", NOW);
        gate.check(account, "wrong", NOW);

        assertThat(gate.check(account, "correct horse", NOW)).isEqualTo(Outcome.SUCCESS);

        for (int i = 1; i < LoginLock.MAX_FAILED_ATTEMPTS; i++) {
            gate.check(account, "wrong", NOW);
        }
        assertThat(account.isLockedAt(NOW)).isFalse();   // the two earlier failures were forgotten
    }

    @Test
    void should_refuse_a_wrong_password_and_lock_the_account_on_the_fifth() {
        Account account = new Account("h:correct horse");

        for (int i = 1; i < LoginLock.MAX_FAILED_ATTEMPTS; i++) {
            assertThat(gate.check(account, "wrong", NOW)).isEqualTo(Outcome.FAILED);
        }
        assertThat(account.isLockedAt(NOW)).isFalse();

        assertThat(gate.check(account, "wrong", NOW)).isEqualTo(Outcome.FAILED);

        assertThat(account.isLockedAt(NOW)).isTrue();
    }

    @Test
    void should_refuse_even_the_right_password_while_locked_and_say_locked_not_failed() {
        Account account = new Account("h:correct horse");
        for (int i = 0; i < LoginLock.MAX_FAILED_ATTEMPTS; i++) {
            gate.check(account, "wrong", NOW);
        }

        assertThat(gate.check(account, "correct horse", NOW.plusSeconds(60))).isEqualTo(Outcome.LOCKED);
        assertThat(gate.check(account, "correct horse", NOW.plus(Duration.ofMinutes(16)))).isEqualTo(Outcome.SUCCESS);   // and not for ever
    }

    @Test
    void should_not_count_a_refusal_while_locked_as_another_failure() {
        Account account = new Account("h:correct horse");
        for (int i = 0; i < LoginLock.MAX_FAILED_ATTEMPTS; i++) {
            gate.check(account, "wrong", NOW);
        }
        Instant lockedUntilAbout = NOW.plus(LoginLock.LOCK_DURATION);

        for (int i = 0; i < 20; i++) {
            gate.check(account, "wrong", NOW.plusSeconds(30));   // guessing while locked
        }

        assertThat(account.isLockedAt(lockedUntilAbout.minusSeconds(1))).isTrue();
        assertThat(account.isLockedAt(lockedUntilAbout)).isFalse();   // the lock was not pushed back by the guesses
    }

    @Test
    void should_cost_the_same_for_every_kind_of_refusal_by_always_comparing_once() {
        Account real = new Account("h:correct horse");
        Account locked = new Account("h:correct horse");
        for (int i = 0; i < LoginLock.MAX_FAILED_ATTEMPTS; i++) {
            gate.check(locked, "wrong", NOW);
        }
        hasher.comparedAgainst.clear();

        gate.check(real, "wrong", NOW);             // wrong password
        gate.check(locked, "correct horse", NOW);   // locked
        gate.check(null, "whatever", NOW);          // no such account
        gate.check(new Account(null), "whatever", NOW);   // an account that has no password yet

        assertThat(hasher.comparedAgainst).hasSize(4);   // one comparison each, so none is quicker than the rest
    }

    @Test
    void should_check_an_unknown_account_and_one_without_a_password_against_the_decoy() {
        assertThat(gate.check(null, "whatever", NOW)).isEqualTo(Outcome.FAILED);
        assertThat(gate.check(new Account(null), "whatever", NOW)).isEqualTo(Outcome.FAILED);

        assertThat(hasher.comparedAgainst).containsExactly(DECOY, DECOY);
    }

    @Test
    void should_never_let_anyone_in_to_an_account_with_no_password_even_with_the_decoys_own_password() {
        // The decoy exists only to cost time: a password that happens to match it must not open anything.
        assertThat(gate.check(new Account(null), "a password nobody has", NOW)).isEqualTo(Outcome.FAILED);
        assertThat(gate.check(null, "a password nobody has", NOW)).isEqualTo(Outcome.FAILED);
    }

    @Test
    void should_hash_the_decoy_once_when_it_is_made_not_for_every_attempt() {
        CountingHasher counting = new CountingHasher() {
            int hashes;

            @Override
            public String hash(String password) {
                hashes++;
                assertThat(hashes).as("the decoy is hashed once, at start-up").isEqualTo(1);
                return super.hash(password);
            }
        };
        PasswordGate once = new PasswordGate(counting);

        once.check(null, "x", NOW);
        once.check(null, "y", NOW);
        once.check(new Account("h:a"), "b", NOW);
    }
}
