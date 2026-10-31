package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

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
    private AccountService service;
    private int issuedEpoch = -1;

    @BeforeEach
    void setUp() {
        when(accounts.findForUpdateByEmailIndex(any())).thenReturn(Optional.empty());
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenAnswer(call -> {
            TravellerAccount saved = call.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", 7L);
            }
            return saved;
        });
        service = at(NOW);
    }

    private AccountService at(Instant now) {
        return new AccountService(accounts, email -> "idx:" + email.strip().toLowerCase(), passwords, new PasswordGate(passwords),
                (id, epoch) -> {
                    issuedEpoch = epoch;
                    return new AccountToken("token-" + id, now.plus(Duration.ofMinutes(30)));
                }, events, Clock.fixed(now, ZoneOffset.UTC),
                new AccountMetrics(registry), TransactionOperations.withoutTransaction(), () -> "raw-token");
    }

    private TravellerAccount active(String password) {
        TravellerAccount account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx:priya@example.com", NOW.minusSeconds(86_400));
        account.setPassword("h:" + password, NOW.minusSeconds(86_000));
        ReflectionTestUtils.setField(account, "id", 7L);
        return account;
    }

    private double count(String meter, String outcome) {
        return registry.get(meter).tag("outcome", outcome).counter().count();
    }

    // ---- links -----------------------------------------------------------------------------------------------------

    @Test
    void should_create_an_account_without_a_password_and_send_a_sign_up_link_for_a_new_address() {
        service.requestPasswordLink("  Priya@Example.com ");

        var saved = org.mockito.ArgumentCaptor.forClass(TravellerAccount.class);
        verify(accounts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().hasPassword()).isFalse();
        verify(events).passwordLinkRequested(eq(7L), eq(new ContactEmail("Priya@Example.com")), eq("raw-token"),
                eq(PasswordLinkPurpose.SIGN_UP), eq(NOW.plus(TravellerAccount.LINK_LIFETIME)));
        assertThat(count("skyhub.account.password.links", "sent")).isEqualTo(1);
    }

    @Test
    void should_send_a_reset_link_to_an_address_that_already_has_a_password_and_leave_the_password_alone() {
        TravellerAccount existing = active("correct horse battery");
        when(accounts.findForUpdateByEmailIndex("idx:priya@example.com")).thenReturn(Optional.of(existing));

        service.requestPasswordLink("priya@example.com");

        verify(events).passwordLinkRequested(eq(7L), any(), eq("raw-token"), eq(PasswordLinkPurpose.RESET), any());
        assertThat(existing.getPasswordHash()).isEqualTo("h:correct horse battery");   // only the link's owner can change it
    }

    @Test
    void should_send_nothing_when_a_link_was_sent_less_than_two_minutes_ago() {
        TravellerAccount existing = active("correct horse battery");
        existing.issueLink("old", NOW.minusSeconds(60));
        when(accounts.findForUpdateByEmailIndex(any())).thenReturn(Optional.of(existing));

        service.requestPasswordLink("priya@example.com");

        verify(events, never()).passwordLinkRequested(anyLong(), any(), any(), any(), any());
        assertThat(count("skyhub.account.password.links", "throttled")).isEqualTo(1);
    }

    @Test
    void should_refuse_something_that_is_not_an_email_address_before_touching_anything() {
        assertThatExceptionOfType(InvalidAccountException.class).isThrownBy(() -> service.requestPasswordLink("not an address"));
        assertThatExceptionOfType(InvalidAccountException.class).isThrownBy(() -> service.requestPasswordLink(null));

        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void should_carry_on_quietly_when_a_parallel_request_created_the_account_first() {
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenThrow(new DataIntegrityViolationException("duplicate"));

        service.requestPasswordLink("priya@example.com");   // no exception: the other request sends the link

        verify(events, never()).passwordLinkRequested(anyLong(), any(), any(), any(), any());
    }

    // ---- choosing a password ---------------------------------------------------------------------------------------

    @Test
    void should_set_the_password_and_spend_the_link() {
        TravellerAccount account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx", NOW.minusSeconds(60));
        account.issueLink(Sha256.hex("raw-token"), NOW.minusSeconds(60));
        when(accounts.findForUpdateByLinkTokenHash(Sha256.hex("raw-token"))).thenReturn(Optional.of(account));

        service.setPassword(" raw-token ", "a long enough passphrase");

        assertThat(account.getPasswordHash()).isEqualTo("h:a long enough passphrase");
        assertThat(account.linkIsValidAt(NOW)).isFalse();
        assertThat(count("skyhub.account.passwords", "set")).isEqualTo(1);
    }

    @Test
    void should_answer_the_same_for_an_unknown_an_used_and_an_expired_link() {
        TravellerAccount expired = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx", NOW.minusSeconds(7200));
        expired.issueLink(Sha256.hex("old"), NOW.minus(Duration.ofHours(2)));
        when(accounts.findForUpdateByLinkTokenHash(Sha256.hex("old"))).thenReturn(Optional.of(expired));

        for (String token : new String[] {"unknown", "old", "", null}) {
            assertThatExceptionOfType(PasswordLinkInvalidException.class).as(String.valueOf(token))
                    .isThrownBy(() -> service.setPassword(token, "a long enough passphrase"));
        }
        assertThat(expired.hasPassword()).isFalse();
        assertThat(count("skyhub.account.passwords", "invalid_link")).isEqualTo(4);
    }

    @Test
    void should_refuse_a_short_or_enormous_password_without_looking_at_the_link() {
        assertThatExceptionOfType(InvalidAccountException.class).isThrownBy(() -> service.setPassword("raw-token", "short"));
        assertThatExceptionOfType(InvalidAccountException.class).isThrownBy(() -> service.setPassword("raw-token", "x".repeat(101)));
        assertThatExceptionOfType(InvalidAccountException.class).isThrownBy(() -> service.setPassword("raw-token", null));

        verify(accounts, never()).findForUpdateByLinkTokenHash(any());
    }

    // ---- signing in ------------------------------------------------------------------------------------------------

    @Test
    void should_sign_in_with_the_right_password_and_return_a_token_for_the_account() {
        when(accounts.findForUpdateByEmailIndex("idx:priya@example.com")).thenReturn(Optional.of(active("correct horse battery")));

        AccountSession session = service.login("Priya@example.com", "correct horse battery");

        assertThat(session.token().value()).isEqualTo("token-7");
        assertThat(session.email()).isEqualTo("priya@example.com");
        assertThat(count("skyhub.account.logins", "success")).isEqualTo(1);
    }

    @Test
    void should_give_one_answer_for_a_wrong_password_an_unknown_address_and_an_account_with_no_password_yet() {
        when(accounts.findForUpdateByEmailIndex("idx:priya@example.com")).thenReturn(Optional.of(active("correct horse battery")));
        TravellerAccount noPassword = TravellerAccount.unverified(new ContactEmail("new@example.com"), "idx:new@example.com", NOW);
        when(accounts.findForUpdateByEmailIndex("idx:new@example.com")).thenReturn(Optional.of(noPassword));

        for (String[] attempt : new String[][] {{"priya@example.com", "wrong wrong wrong"}, {"nobody@example.com", "whatever whatever"},
                {"new@example.com", "whatever whatever"}, {null, null}}) {
            assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.login(attempt[0], attempt[1]));
        }
        assertThat(count("skyhub.account.logins", "failed")).isEqualTo(4);
    }

    @Test
    void should_lock_after_five_wrong_passwords_and_refuse_even_the_right_one_while_locked() {
        TravellerAccount account = active("correct horse battery");
        when(accounts.findForUpdateByEmailIndex(any())).thenReturn(Optional.of(account));
        for (int i = 0; i < TravellerAccount.MAX_FAILED_ATTEMPTS; i++) {
            assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.login("priya@example.com", "wrong wrong wrong"));
        }

        assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.login("priya@example.com", "correct horse battery"));
        assertThat(count("skyhub.account.logins", "locked")).isEqualTo(1);

        AccountService later = at(NOW.plus(Duration.ofMinutes(16)));
        assertThat(later.login("priya@example.com", "correct horse battery").token().value()).isEqualTo("token-7");
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() {
        when(accounts.findById(99L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.emailOf(99L));
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    // ---- sessions (ADR-0041) --------------------------------------------------------------------------------------------

    @Test
    void should_issue_the_token_under_the_epoch_the_account_has_now() {
        TravellerAccount account = active("correct horse battery");
        ReflectionTestUtils.setField(account, "sessionEpoch", 4);
        when(accounts.findForUpdateByEmailIndex("idx:priya@example.com")).thenReturn(Optional.of(account));

        service.login("priya@example.com", "correct horse battery");

        assertThat(issuedEpoch).isEqualTo(4);
    }

    @Test
    void should_end_every_open_session_when_a_password_is_chosen_with_a_link() {
        TravellerAccount account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx", NOW.minusSeconds(60));
        account.issueLink(Sha256.hex("raw-token"), NOW.minusSeconds(60));
        when(accounts.findForUpdateByLinkTokenHash(Sha256.hex("raw-token"))).thenReturn(Optional.of(account));
        int before = account.getSessionEpoch();

        service.setPassword("raw-token", "a long enough passphrase");

        assertThat(account.getSessionEpoch()).isEqualTo(before + 1);
    }
}
