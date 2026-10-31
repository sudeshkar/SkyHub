package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The one rule for locking an account after wrong passwords (ADR-0045), shared by staff and traveller accounts. */
class LoginLockTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private final LoginLock lock = new LoginLock();

    private void fail(int times, Instant at) {
        for (int i = 0; i < times; i++) {
            lock.recordFailure(at);
        }
    }

    @Test
    void should_start_unlocked() {
        assertThat(lock.isLockedAt(NOW)).isFalse();
    }

    @Test
    void should_lock_on_the_fifth_wrong_password_and_not_before() {
        for (int i = 1; i < LoginLock.MAX_FAILED_ATTEMPTS; i++) {
            lock.recordFailure(NOW);
            assertThat(lock.isLockedAt(NOW)).as("after %d failures", i).isFalse();
        }

        lock.recordFailure(NOW);

        assertThat(lock.isLockedAt(NOW)).isTrue();
    }

    @Test
    void should_stay_locked_for_fifteen_minutes_and_not_a_moment_longer() {
        fail(LoginLock.MAX_FAILED_ATTEMPTS, NOW);

        assertThat(lock.isLockedAt(NOW.plus(Duration.ofMinutes(14)).plusSeconds(59))).isTrue();
        assertThat(lock.isLockedAt(NOW.plus(Duration.ofMinutes(15)))).isFalse();
        assertThat(LoginLock.LOCK_DURATION).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void should_forget_the_count_on_success_so_slow_guessing_never_adds_up() {
        fail(LoginLock.MAX_FAILED_ATTEMPTS - 1, NOW);
        lock.recordSuccess();
        lock.recordFailure(NOW);

        assertThat(lock.isLockedAt(NOW)).isFalse();   // the count restarted at one

        fail(LoginLock.MAX_FAILED_ATTEMPTS - 1, NOW);
        assertThat(lock.isLockedAt(NOW)).isTrue();    // and four more is five again
    }

    @Test
    void should_lift_a_lock_on_success_straight_away() {
        fail(LoginLock.MAX_FAILED_ATTEMPTS, NOW);

        lock.recordSuccess();

        assertThat(lock.isLockedAt(NOW)).isFalse();
    }

    @Test
    void should_start_counting_afresh_once_a_lock_has_run_out_so_one_more_mistake_does_not_relock() {
        fail(LoginLock.MAX_FAILED_ATTEMPTS, NOW);
        Instant later = NOW.plus(Duration.ofMinutes(16));

        lock.recordFailure(later);

        assertThat(lock.isLockedAt(later)).isFalse();
        fail(LoginLock.MAX_FAILED_ATTEMPTS - 2, later);
        assertThat(lock.isLockedAt(later)).isFalse();   // four failures since the lock ran out
        lock.recordFailure(later);
        assertThat(lock.isLockedAt(later)).isTrue();    // the fifth
    }

    @Test
    void should_clear_everything_when_asked_to_for_instance_when_a_new_password_is_chosen() {
        fail(LoginLock.MAX_FAILED_ATTEMPTS, NOW);

        lock.clear();

        assertThat(lock.isLockedAt(NOW)).isFalse();
        fail(LoginLock.MAX_FAILED_ATTEMPTS - 1, NOW);
        assertThat(lock.isLockedAt(NOW)).isFalse();   // the count is back to nothing, not left at five
    }
}
