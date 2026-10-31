package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Moving an account to another address (ADR-0039): the rules that belong to the account itself. */
class TravellerAccountEmailChangeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final ContactEmail NEW = new ContactEmail("priya.new@example.com");

    private static TravellerAccount account() {
        TravellerAccount account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx:old", NOW.minusSeconds(86_400));
        account.setPassword("hash", NOW.minusSeconds(86_000));
        return account;
    }

    @Test
    void should_keep_the_old_address_until_the_change_is_confirmed() {
        TravellerAccount account = account();

        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW);

        assertThat(account.getEmail().value()).isEqualTo("priya@example.com");
        assertThat(account.getEmailIndex()).isEqualTo("idx:old");
    }

    @Test
    void should_accept_the_change_link_for_one_hour_and_not_after() {
        TravellerAccount account = account();
        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW);

        assertThat(account.emailChangeIsValidFor("token-hash", NOW.plus(Duration.ofMinutes(59)))).isTrue();
        assertThat(account.emailChangeIsValidFor("token-hash", NOW.plus(Duration.ofHours(1)))).isFalse();
    }

    @Test
    void should_refuse_a_token_that_is_not_the_one_issued_and_any_token_when_none_is_pending() {
        TravellerAccount account = account();

        assertThat(account.emailChangeIsValidFor("anything", NOW)).isFalse();
        assertThat(account.emailChangeIsValidFor(null, NOW)).isFalse();

        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW);

        assertThat(account.emailChangeIsValidFor("other-hash", NOW)).isFalse();
        assertThat(account.emailChangeIsValidFor(null, NOW)).isFalse();
    }

    @Test
    void should_let_only_the_newest_request_work() {
        TravellerAccount account = account();
        account.requestEmailChange(NEW, "idx:new", "first", NOW);
        account.requestEmailChange(new ContactEmail("other@example.com"), "idx:other", "second", NOW.plusSeconds(300));

        assertThat(account.emailChangeIsValidFor("first", NOW.plusSeconds(301))).isFalse();
        assertThat(account.emailChangeIsValidFor("second", NOW.plusSeconds(301))).isTrue();
    }

    @Test
    void should_not_be_asked_again_within_two_minutes_so_the_form_cannot_flood_an_inbox() {
        TravellerAccount account = account();
        assertThat(account.mayBeAskedToChangeEmailAt(NOW)).isTrue();

        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW);

        assertThat(account.mayBeAskedToChangeEmailAt(NOW.plusSeconds(119))).isFalse();
        assertThat(account.mayBeAskedToChangeEmailAt(NOW.plusSeconds(120))).isTrue();
    }

    @Test
    void should_move_to_the_new_address_and_spend_the_request_when_confirmed() {
        TravellerAccount account = account();
        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW);

        account.confirmEmailChange(NOW.plusSeconds(60));

        assertThat(account.getEmail()).isEqualTo(NEW);
        assertThat(account.getEmailIndex()).isEqualTo("idx:new");
        assertThat(account.emailChangeIsValidFor("token-hash", NOW.plusSeconds(61))).isFalse();   // it works once
        assertThat(account.hasPassword()).isTrue();
    }

    @Test
    void should_stop_any_password_link_that_was_already_out_when_the_address_changes() {
        // A reset link was emailed to the OLD address; whoever still reads that mailbox must not be able to use it afterwards.
        TravellerAccount account = account();
        account.issueLink("reset-hash", NOW);
        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW.plusSeconds(10));

        account.confirmEmailChange(NOW.plusSeconds(60));

        assertThat(account.linkIsValidAt(NOW.plusSeconds(61))).isFalse();
    }

    @Test
    void should_keep_the_lock_state_it_had_since_changing_the_address_is_not_a_way_to_clear_it() {
        TravellerAccount account = account();
        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS; i++) {
            account.recordFailedLogin(NOW);
        }
        account.requestEmailChange(NEW, "idx:new", "token-hash", NOW);

        account.confirmEmailChange(NOW.plusSeconds(60));

        assertThat(account.isLockedAt(NOW.plusSeconds(61))).isTrue();
    }

    @Test
    void should_know_whether_an_address_is_its_current_one() {
        assertThat(account().hasEmailIndex("idx:old")).isTrue();
        assertThat(account().hasEmailIndex("idx:new")).isFalse();
    }

    @Test
    void should_not_print_the_pending_address_or_the_token_hash() {
        TravellerAccount account = account();
        account.requestEmailChange(NEW, "idx:new", "secret-hash", NOW);

        assertThat(account.toString()).doesNotContain("priya.new").doesNotContain("secret-hash");
    }
}
