package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StaffAccountTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private static StaffAccount account() {
        return StaffAccount.create("priya.ops", "$2a$hash", StaffRole.OPS, NOW);
    }

    @Test
    void should_lock_for_fifteen_minutes_after_five_wrong_passwords() {
        StaffAccount account = account();

        for (int i = 0; i < 4; i++) {
            account.recordFailedLogin(NOW);
            assertThat(account.isLockedAt(NOW)).as("after %d failures", i + 1).isFalse();
        }
        account.recordFailedLogin(NOW);

        assertThat(account.isLockedAt(NOW)).isTrue();
        assertThat(account.isLockedAt(NOW.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(account.isLockedAt(NOW.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void should_start_counting_again_after_a_lock_so_one_more_mistake_does_not_relock() {
        StaffAccount account = account();
        for (int i = 0; i < 5; i++) {
            account.recordFailedLogin(NOW);
        }
        Instant later = NOW.plus(Duration.ofMinutes(20));

        account.recordFailedLogin(later);

        assertThat(account.isLockedAt(later)).isFalse();
    }

    @Test
    void should_forget_earlier_mistakes_after_a_correct_login() {
        StaffAccount account = account();
        for (int i = 0; i < 4; i++) {
            account.recordFailedLogin(NOW);
        }

        account.recordSuccessfulLogin();
        account.recordFailedLogin(NOW);

        assertThat(account.isLockedAt(NOW)).isFalse();
    }

    @Test
    void should_normalise_the_username_to_lower_case() {
        assertThat(StaffAccount.create("Priya.Ops", "$2a$hash", StaffRole.OPS, NOW).getUsername()).isEqualTo("priya.ops");
    }

    @Test
    void should_refuse_a_username_that_is_not_a_plain_handle() {
        for (String bad : new String[] {"", "ab", "1priya", "priya ops", "priya@example.com", "x".repeat(41), "../etc"}) {
            assertThatExceptionOfType(InvalidStaffAccountException.class)
                    .as(bad)
                    .isThrownBy(() -> StaffAccount.create(bad, "$2a$hash", StaffRole.OPS, NOW));
        }
    }

    @Test
    void should_never_print_the_password_hash() {
        assertThat(account().toString()).doesNotContain("$2a$hash");
    }

    @Test
    void should_know_which_role_may_do_what() {
        assertThat(StaffRole.OPS.mayVerifyBoardingPasses()).isTrue();
        assertThat(StaffRole.ADMIN.mayVerifyBoardingPasses()).isTrue();
        assertThat(StaffRole.OPS.mayViewAffectedBookings()).isTrue();
        assertThat(StaffRole.ADMIN.mayViewAffectedBookings()).isTrue();
        assertThat(StaffRole.OPS.mayRecordDisruptions()).isTrue();
        assertThat(StaffRole.OPS.mayCreateStaff()).isFalse();
        assertThat(StaffRole.ADMIN.mayRecordDisruptions()).isTrue();
        assertThat(StaffRole.ADMIN.mayCreateStaff()).isTrue();
    }
}
