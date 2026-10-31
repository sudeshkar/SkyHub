package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Ending every open session when an account's credentials change (ADR-0041): the counter that tokens are checked against. */
class TravellerAccountSessionsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private static TravellerAccount account() {
        TravellerAccount account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx:old", NOW.minusSeconds(86_400));
        account.setPassword("hash", NOW.minusSeconds(86_000));
        return account;
    }

    @Test
    void should_start_every_account_at_epoch_zero_so_tokens_issued_before_the_change_stay_valid() {
        assertThat(TravellerAccount.unverified(new ContactEmail("a@example.com"), "idx", NOW).getSessionEpoch()).isZero();
    }

    @Test
    void should_end_every_session_when_the_address_is_confirmed() {
        TravellerAccount account = account();
        int before = account.getSessionEpoch();
        account.requestEmailChange(new ContactEmail("priya.new@example.com"), "idx:new", "token-hash", NOW);

        account.confirmEmailChange(NOW.plusSeconds(60));

        assertThat(account.getSessionEpoch()).isEqualTo(before + 1);
    }

    @Test
    void should_end_every_session_when_a_password_is_chosen_with_an_emailed_link() {
        TravellerAccount account = account();
        int before = account.getSessionEpoch();
        account.issueLink("reset-hash", NOW);

        account.setPassword("new-hash", NOW.plusSeconds(60));

        assertThat(account.getSessionEpoch()).isEqualTo(before + 1);
    }

    @Test
    void should_leave_the_epoch_alone_for_things_that_are_not_a_change_of_credentials() {
        TravellerAccount account = account();
        int before = account.getSessionEpoch();

        account.recordFailedLogin(NOW);
        account.recordSuccessfulLogin();
        account.issueLink("hash", NOW);
        account.requestEmailChange(new ContactEmail("priya.new@example.com"), "idx:new", "token-hash", NOW);

        assertThat(account.getSessionEpoch()).isEqualTo(before);
    }

    @Test
    void should_count_each_change_separately_so_a_session_from_before_any_of_them_stays_revoked() {
        TravellerAccount account = account();
        int issuedUnder = account.getSessionEpoch();
        account.issueLink("one", NOW);
        account.setPassword("h1", NOW.plusSeconds(10));
        account.requestEmailChange(new ContactEmail("priya.new@example.com"), "idx:new", "token-hash", NOW.plusSeconds(20));
        account.confirmEmailChange(NOW.plusSeconds(30));

        assertThat(account.getSessionEpoch()).isEqualTo(issuedUnder + 2);
    }

    @Test
    void should_end_every_session_on_request_without_touching_anything_else_about_the_account() {
        TravellerAccount account = account();
        int before = account.getSessionEpoch();
        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS; i++) {
            account.recordFailedLogin(NOW);   // a lock must survive it: signing out everywhere is not a way to clear one
        }

        account.endAllSessions();

        assertThat(account.getSessionEpoch()).isEqualTo(before + 1);
        assertThat(account.hasPassword()).isTrue();
        assertThat(account.getEmail().value()).isEqualTo("priya@example.com");
        assertThat(account.isLockedAt(NOW)).isTrue();
    }

    @Test
    void should_end_the_sessions_again_each_time_it_is_asked() {
        TravellerAccount account = account();
        int before = account.getSessionEpoch();

        account.endAllSessions();
        account.endAllSessions();

        assertThat(account.getSessionEpoch()).isEqualTo(before + 2);
    }
}
