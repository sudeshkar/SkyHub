package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.InvalidAccountException;
import com.skyhub.booking.domain.TravellerAccount;
import com.skyhub.booking.persistence.TravellerAccountRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

/** Changing the account's address (ADR-0039): password, then a link to the new address, then the change. */
class AccountEmailChangeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String PASSWORD = "correct horse battery";

    private final TravellerAccountRepository accounts = mock(TravellerAccountRepository.class);
    private final AccountEvents events = mock(AccountEvents.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PasswordHasher passwords = new PasswordHasher() {
        @Override
        public String hash(String password) {
            return "h:" + password;
        }

        @Override
        public boolean matches(String password, String hash) {
            return hash.equals("h:" + password);
        }
    };
    private TravellerAccount account;
    private AccountService service;

    @BeforeEach
    void setUp() {
        account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx:priya@example.com", NOW.minusSeconds(86_400));
        account.setPassword("h:" + PASSWORD, NOW.minusSeconds(86_000));
        ReflectionTestUtils.setField(account, "id", 7L);
        when(accounts.findForUpdateById(7L)).thenReturn(Optional.of(account));
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenAnswer(call -> call.getArgument(0));
        service = at(NOW);
    }

    private AccountService at(Instant now) {
        return new AccountService(accounts, email -> "idx:" + email.strip().toLowerCase(), passwords,
                (id, epoch) -> new AccountToken("token-" + id, now.plus(Duration.ofMinutes(30))), events, Clock.fixed(now, ZoneOffset.UTC),
                new AccountMetrics(registry), TransactionOperations.withoutTransaction(), () -> "raw-token");
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.account.email.changes").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---- asking ------------------------------------------------------------------------------------------------------

    @Test
    void should_keep_the_new_address_pending_and_send_the_link_to_the_new_address_only() {
        service.requestEmailChange(7L, " Priya.New@Example.com ", PASSWORD);

        verify(events).emailChangeRequested(eq(7L), eq(new ContactEmail("Priya.New@Example.com")), eq("raw-token"),   // as typed; matching ignores case
                eq(NOW.plus(TravellerAccount.LINK_LIFETIME)));
        assertThat(account.getEmail().value()).isEqualTo("priya@example.com");        // not changed yet
        assertThat(account.emailChangeIsValidFor(Sha256.hex("raw-token"), NOW)).isTrue();   // only the hash was kept
        assertThat(count("requested")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_wrong_password_send_nothing_and_count_it_toward_the_lock() {
        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS; i++) {
            assertThatExceptionOfType(PasswordNotAcceptedException.class)
                    .isThrownBy(() -> service.requestEmailChange(7L, "priya.new@example.com", "wrong wrong wrong"));
        }

        assertThat(account.isLockedAt(NOW)).isTrue();
        // Locked: even the right password is refused, exactly as at sign-in.
        assertThatExceptionOfType(PasswordNotAcceptedException.class)
                .isThrownBy(() -> service.requestEmailChange(7L, "priya.new@example.com", PASSWORD));
        verifyNoInteractions(events);
        assertThat(count("password_refused")).isEqualTo(6);
    }

    @Test
    void should_refuse_an_account_that_has_no_password_since_it_cannot_prove_who_is_asking() {
        TravellerAccount unproven = TravellerAccount.unverified(new ContactEmail("x@example.com"), "idx:x@example.com", NOW);
        ReflectionTestUtils.setField(unproven, "id", 8L);
        when(accounts.findForUpdateById(8L)).thenReturn(Optional.of(unproven));

        assertThatExceptionOfType(PasswordNotAcceptedException.class).isThrownBy(() -> service.requestEmailChange(8L, "y@example.com", "anything long enough"));
        verifyNoInteractions(events);
    }

    @Test
    void should_refuse_something_that_is_not_an_address_and_the_address_the_account_already_has() {
        assertThatExceptionOfType(InvalidAccountException.class).isThrownBy(() -> service.requestEmailChange(7L, "nope", PASSWORD));
        assertThatExceptionOfType(InvalidAccountException.class)
                .isThrownBy(() -> service.requestEmailChange(7L, "PRIYA@example.com", PASSWORD))
                .withMessageContaining("already your address");
        verifyNoInteractions(events);
    }

    @Test
    void should_answer_the_same_and_send_nothing_when_the_new_address_belongs_to_another_account() {
        when(accounts.existsByEmailIndex("idx:taken@example.com")).thenReturn(true);

        service.requestEmailChange(7L, "taken@example.com", PASSWORD);   // returns normally: nothing says it is taken

        verifyNoInteractions(events);
        assertThat(account.emailChangeIsValidFor(Sha256.hex("raw-token"), NOW)).isFalse();
        assertThat(count("taken")).isEqualTo(1);
    }

    @Test
    void should_send_nothing_when_a_request_was_made_less_than_two_minutes_ago() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        AccountService soon = at(NOW.plusSeconds(60));

        soon.requestEmailChange(7L, "priya.other@example.com", PASSWORD);

        verify(events).emailChangeRequested(any(Long.class), any(ContactEmail.class), any(String.class), any(Instant.class));   // only the first
        assertThat(count("throttled")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() {
        when(accounts.findForUpdateById(99L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.requestEmailChange(99L, "a@example.com", PASSWORD));
        assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.confirmEmailChange(99L, "raw-token"));
    }

    // ---- confirming ----------------------------------------------------------------------------------------------------

    @Test
    void should_move_the_account_to_the_new_address_with_the_emailed_token() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);

        service.confirmEmailChange(7L, " raw-token ");

        assertThat(account.getEmail().value()).isEqualTo("priya.new@example.com");
        assertThat(account.getEmailIndex()).isEqualTo("idx:priya.new@example.com");
        assertThat(count("confirmed")).isEqualTo(1);
    }

    @Test
    void should_stop_a_password_link_that_was_already_out_to_the_old_address() {
        account.issueLink(Sha256.hex("reset-token"), NOW);
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);

        service.confirmEmailChange(7L, "raw-token");

        assertThat(account.linkIsValidAt(NOW)).isFalse();
    }

    @Test
    void should_answer_the_same_for_a_wrong_an_expired_a_used_and_a_missing_token() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        AccountService muchLater = at(NOW.plus(Duration.ofHours(2)));

        for (String token : new String[] {"wrong", "", null}) {
            assertThatExceptionOfType(PasswordLinkInvalidException.class).as(String.valueOf(token))
                    .isThrownBy(() -> service.confirmEmailChange(7L, token));
        }
        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> muchLater.confirmEmailChange(7L, "raw-token"));   // expired
        service.confirmEmailChange(7L, "raw-token");
        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.confirmEmailChange(7L, "raw-token"));       // used

        assertThat(account.getEmail().value()).isEqualTo("priya.new@example.com");   // changed once, by the one good call
        assertThat(count("invalid_link")).isEqualTo(5);
    }

    @Test
    void should_answer_the_same_when_another_account_took_the_address_in_the_meantime() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenThrow(new DataIntegrityViolationException("uq_traveller_account_email"));

        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.confirmEmailChange(7L, "raw-token"));
        assertThat(count("invalid_link")).isEqualTo(1);
    }

    @Test
    void should_change_nothing_when_nothing_is_pending() {
        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.confirmEmailChange(7L, "raw-token"));

        assertThat(account.getEmail().value()).isEqualTo("priya@example.com");
        verify(accounts, never()).saveAndFlush(any(TravellerAccount.class));
    }

    // ---- telling the old address (ADR-0040) ----------------------------------------------------------------------------

    @Test
    void should_tell_the_old_address_once_when_the_account_moves_naming_the_new_one() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);

        service.confirmEmailChange(7L, "raw-token");

        verify(events).emailChanged(7L, new ContactEmail("priya@example.com"), new ContactEmail("priya.new@example.com"));
    }

    @Test
    void should_tell_nobody_when_the_change_is_refused() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        org.mockito.Mockito.clearInvocations(events);

        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.confirmEmailChange(7L, "wrong"));
        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> at(NOW.plus(Duration.ofHours(2))).confirmEmailChange(7L, "raw-token"));

        verifyNoInteractions(events);
    }

    @Test
    void should_tell_nobody_when_another_account_took_the_address_in_the_meantime() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        org.mockito.Mockito.clearInvocations(events);
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenThrow(new DataIntegrityViolationException("uq_traveller_account_email"));

        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.confirmEmailChange(7L, "raw-token"));

        verifyNoInteractions(events);
    }

    @Test
    void should_not_tell_anyone_just_for_asking_since_nothing_has_changed_yet() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);

        verify(events, never()).emailChanged(any(Long.class), any(ContactEmail.class), any(ContactEmail.class));
    }

    @Test
    void should_end_every_open_session_when_the_account_moves_and_not_when_it_merely_asks() {
        int before = account.getSessionEpoch();

        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        assertThat(account.getSessionEpoch()).isEqualTo(before);

        service.confirmEmailChange(7L, "raw-token");
        assertThat(account.getSessionEpoch()).isEqualTo(before + 1);
    }

    @Test
    void should_keep_every_session_when_the_change_is_refused() {
        service.requestEmailChange(7L, "priya.new@example.com", PASSWORD);
        int before = account.getSessionEpoch();

        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.confirmEmailChange(7L, "wrong"));

        assertThat(account.getSessionEpoch()).isEqualTo(before);
    }
}
