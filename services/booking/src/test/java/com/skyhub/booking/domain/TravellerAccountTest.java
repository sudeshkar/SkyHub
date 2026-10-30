package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TravellerAccountTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private static TravellerAccount account() {
        return TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx", NOW);
    }

    @Test
    void should_start_without_a_password_so_nobody_can_sign_in_to_an_address_they_have_not_proved() {
        TravellerAccount account = account();

        assertThat(account.hasPassword()).isFalse();
        assertThat(account.linkIsValidAt(NOW)).isFalse();
    }

    @Test
    void should_accept_a_link_for_one_hour_and_not_after() {
        TravellerAccount account = account();
        account.issueLink("hash", NOW);

        assertThat(account.linkIsValidAt(NOW.plus(Duration.ofMinutes(59)))).isTrue();
        assertThat(account.linkIsValidAt(NOW.plus(Duration.ofHours(1)))).isFalse();
    }

    @Test
    void should_let_only_the_newest_link_work() {
        TravellerAccount account = account();
        account.issueLink("first", NOW);
        account.issueLink("second", NOW.plusSeconds(300));

        assertThat(account.linkIsValidAt(NOW.plusSeconds(301))).isTrue();   // the newest, and the only one the row remembers
    }

    @Test
    void should_not_send_another_link_within_two_minutes_of_the_last() {
        TravellerAccount account = account();
        assertThat(account.mayBeSentLinkAt(NOW)).isTrue();
        account.issueLink("hash", NOW);

        assertThat(account.mayBeSentLinkAt(NOW.plusSeconds(119))).isFalse();
        assertThat(account.mayBeSentLinkAt(NOW.plusSeconds(120))).isTrue();
    }

    @Test
    void should_confirm_the_address_spend_the_link_and_clear_a_lock_when_the_password_is_chosen() {
        TravellerAccount account = account();
        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS; i++) {
            account.recordFailedLogin(NOW);
        }
        account.issueLink("hash", NOW);

        account.setPassword("bcrypt-hash", NOW.plusSeconds(60));

        assertThat(account.hasPassword()).isTrue();
        assertThat(account.linkIsValidAt(NOW.plusSeconds(61))).isFalse();   // spent
        assertThat(account.isLockedAt(NOW.plusSeconds(61))).isFalse();
    }

    @Test
    void should_lock_for_fifteen_minutes_after_five_wrong_passwords_and_forget_the_count_on_success() {
        TravellerAccount account = account();
        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS - 1; i++) {
            account.recordFailedLogin(NOW);
        }
        account.recordSuccessfulLogin();
        account.recordFailedLogin(NOW);
        assertThat(account.isLockedAt(NOW)).isFalse();   // the count restarted

        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS; i++) {
            account.recordFailedLogin(NOW);
        }

        assertThat(account.isLockedAt(NOW.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(account.isLockedAt(NOW.plus(Duration.ofMinutes(15)))).isFalse();
    }

    @Test
    void should_never_print_the_address_or_any_secret() {
        TravellerAccount account = account();
        account.issueLink("secret-hash", NOW);
        account.setPassword("bcrypt-secret", NOW);

        assertThat(account.toString()).doesNotContain("priya@example.com").doesNotContain("secret");
    }
}
