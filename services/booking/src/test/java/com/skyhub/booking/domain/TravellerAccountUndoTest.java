package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Taking an account back after it was moved without its owner (ADR-0054): the rules that belong to the account itself. */
class TravellerAccountUndoTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final ContactEmail OLD = new ContactEmail("priya@example.com");
    private static final ContactEmail NEW = new ContactEmail("someone.else@example.com");

    /** An account that has been moved from OLD to NEW, with the undo token "undo-hash" issued at NOW. */
    private static TravellerAccount moved() {
        TravellerAccount account = TravellerAccount.unverified(OLD, "idx:old", NOW.minusSeconds(86_400));
        account.setPassword("hash", NOW.minusSeconds(86_000));
        account.requestEmailChange(NEW, "idx:new", "change-hash", NOW.minusSeconds(60));
        account.confirmEmailChange(NOW, "undo-hash");
        return account;
    }

    @Test
    void should_accept_the_undo_token_for_seven_days_and_not_after() {
        TravellerAccount account = moved();

        assertThat(account.undoEmailChangeIsValidFor("undo-hash", NOW.plus(Duration.ofDays(7)).minusSeconds(1))).isTrue();
        assertThat(account.undoEmailChangeIsValidFor("undo-hash", NOW.plus(Duration.ofDays(7)))).isFalse();
        assertThat(account.getUndoExpiresAt()).isEqualTo(NOW.plus(TravellerAccount.UNDO_LIFETIME));
    }

    @Test
    void should_refuse_any_other_token_and_any_token_before_a_move() {
        TravellerAccount fresh = TravellerAccount.unverified(OLD, "idx:old", NOW);

        assertThat(fresh.undoEmailChangeIsValidFor("undo-hash", NOW)).isFalse();
        assertThat(fresh.undoEmailChangeIsValidFor(null, NOW)).isFalse();
        assertThat(moved().undoEmailChangeIsValidFor("other", NOW)).isFalse();
        assertThat(moved().undoEmailChangeIsValidFor(null, NOW)).isFalse();
    }

    @Test
    void should_put_the_old_address_back() {
        TravellerAccount account = moved();
        assertThat(account.getEmail()).isEqualTo(NEW);

        account.undoEmailChange(NOW.plusSeconds(3600));

        assertThat(account.getEmail()).isEqualTo(OLD);
        assertThat(account.hasEmailIndex("idx:old")).isTrue();
        assertThat(account.hasEmailIndex("idx:new")).isFalse();
    }

    @Test
    void should_end_every_session_and_throw_away_the_password_the_other_person_may_know() {
        TravellerAccount account = moved();
        int epoch = account.getSessionEpoch();

        account.undoEmailChange(NOW.plusSeconds(3600));

        assertThat(account.getSessionEpoch()).isGreaterThan(epoch);
        assertThat(account.hasPassword()).isFalse();   // it must be chosen again, through a link to the restored address
    }

    @Test
    void should_stop_every_link_that_was_out_and_clear_a_lock() {
        TravellerAccount account = moved();
        account.issueLink("link-hash", NOW.plusSeconds(10));
        account.requestEmailChange(new ContactEmail("third@example.com"), "idx:third", "second-change", NOW.plusSeconds(20));

        account.undoEmailChange(NOW.plusSeconds(3600));

        assertThat(account.linkIsValidAt(NOW.plusSeconds(3601))).isFalse();
        assertThat(account.emailChangeIsValidFor("second-change", NOW.plusSeconds(3601))).isFalse();
        assertThat(account.isLockedAt(NOW.plusSeconds(3601))).isFalse();
    }

    @Test
    void should_work_once() {
        TravellerAccount account = moved();
        account.undoEmailChange(NOW.plusSeconds(3600));

        assertThat(account.undoEmailChangeIsValidFor("undo-hash", NOW.plusSeconds(3601))).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> account.undoEmailChange(NOW.plusSeconds(3602)));
    }

    @Test
    void should_let_only_the_latest_move_be_undone() {
        TravellerAccount account = moved();
        account.requestEmailChange(new ContactEmail("third@example.com"), "idx:third", "second-change", NOW.plusSeconds(120));
        account.confirmEmailChange(NOW.plusSeconds(180), "second-undo");

        assertThat(account.undoEmailChangeIsValidFor("undo-hash", NOW.plusSeconds(181))).isFalse();
        assertThat(account.undoEmailChangeIsValidFor("second-undo", NOW.plusSeconds(181))).isTrue();
        account.undoEmailChange(NOW.plusSeconds(200));
        assertThat(account.getEmail()).isEqualTo(NEW);   // back one step, to where it was before the latest move
    }
}
