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
import com.skyhub.booking.domain.TravellerAccount;
import com.skyhub.booking.persistence.TravellerAccountRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

/** The owner of the old address takes the account back (ADR-0054). */
class AccountUndoEmailChangeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofDays(1));

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

    /** Hands out undo-1, undo-2 ... so the undo token and the password link token are told apart. */
    private AccountService at(Instant now) {
        AtomicInteger n = new AtomicInteger();
        return new AccountService(accounts, email -> "idx:" + email.strip().toLowerCase(), passwords, new PasswordGate(passwords),
                (id, epoch) -> new AccountToken("token-" + id, now.plus(Duration.ofMinutes(30))), events, Clock.fixed(now, ZoneOffset.UTC),
                new AccountMetrics(registry), TransactionOperations.withoutTransaction(), () -> "tok-" + n.incrementAndGet());
    }

    /** The account has been moved from priya@ to someone.else@, and the undo token was "undo-token". */
    @BeforeEach
    void setUp() {
        account = TravellerAccount.unverified(new ContactEmail("priya@example.com"), "idx:priya@example.com", NOW.minusSeconds(86_400));
        account.setPassword("h:secret", NOW.minusSeconds(86_000));
        account.requestEmailChange(new ContactEmail("someone.else@example.com"), "idx:someone.else@example.com", "change-hash", NOW.minusSeconds(60));
        account.confirmEmailChange(NOW, Sha256.hex("undo-token"));
        ReflectionTestUtils.setField(account, "id", 7L);
        when(accounts.findForUpdateByUndoTokenHash(Sha256.hex("undo-token"))).thenReturn(Optional.of(account));
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenAnswer(call -> call.getArgument(0));
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.account.email.changes").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_put_the_old_address_back_end_every_session_and_throw_the_password_away() {
        int epoch = account.getSessionEpoch();

        at(LATER).undoEmailChange(" undo-token ");

        assertThat(account.getEmail().value()).isEqualTo("priya@example.com");
        assertThat(account.hasEmailIndex("idx:priya@example.com")).isTrue();
        assertThat(account.getSessionEpoch()).isGreaterThan(epoch);
        assertThat(account.hasPassword()).isFalse();
        assertThat(count("undone")).isEqualTo(1);
    }

    @Test
    void should_send_a_link_to_the_restored_address_so_its_owner_can_choose_a_new_password() {
        at(LATER).undoEmailChange("undo-token");

        verify(events).passwordLinkRequested(eq(7L), eq(new ContactEmail("priya@example.com")), eq("tok-1"),
                eq(PasswordLinkPurpose.RESET), eq(LATER.plus(TravellerAccount.LINK_LIFETIME)));
        assertThat(account.linkIsValidAt(LATER)).isTrue();   // and that is the only link that works
    }

    @Test
    void should_answer_the_same_for_a_wrong_an_expired_a_used_and_a_missing_token() {
        AccountService service = at(LATER);

        for (String token : new String[] {"wrong", "", null}) {
            assertThatExceptionOfType(PasswordLinkInvalidException.class).as(String.valueOf(token)).isThrownBy(() -> service.undoEmailChange(token));
        }
        assertThatExceptionOfType(PasswordLinkInvalidException.class)
                .isThrownBy(() -> at(NOW.plus(Duration.ofDays(8))).undoEmailChange("undo-token"));   // expired
        service.undoEmailChange("undo-token");
        when(accounts.findForUpdateByUndoTokenHash(Sha256.hex("undo-token"))).thenReturn(Optional.empty());   // as the table now holds it
        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> service.undoEmailChange("undo-token"));   // used

        assertThat(account.getEmail().value()).isEqualTo("priya@example.com");   // put back once, by the one good call
        assertThat(count("undo_invalid")).isEqualTo(5);
    }

    @Test
    void should_change_nothing_and_send_nothing_when_the_token_is_not_good() {
        org.mockito.Mockito.clearInvocations(events);

        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> at(LATER).undoEmailChange("wrong"));

        assertThat(account.getEmail().value()).isEqualTo("someone.else@example.com");
        assertThat(account.hasPassword()).isTrue();
        verifyNoInteractions(events);
        verify(accounts, never()).saveAndFlush(any(TravellerAccount.class));
    }

    @Test
    void should_answer_the_same_when_the_old_address_has_been_taken_by_another_account_since() {
        when(accounts.saveAndFlush(any(TravellerAccount.class))).thenThrow(new DataIntegrityViolationException("uq_traveller_account_email"));
        org.mockito.Mockito.clearInvocations(events);

        assertThatExceptionOfType(PasswordLinkInvalidException.class).isThrownBy(() -> at(LATER).undoEmailChange("undo-token"));

        verifyNoInteractions(events);   // nobody is emailed a link that cannot work
        assertThat(count("undo_invalid")).isEqualTo(1);
    }

    @Test
    void should_hand_the_old_address_an_undo_token_when_the_account_moves() {
        AccountService service = at(NOW);
        TravellerAccount fresh = TravellerAccount.unverified(new ContactEmail("sam@example.com"), "idx:sam@example.com", NOW.minusSeconds(86_400));
        fresh.setPassword("h:secret", NOW.minusSeconds(86_000));
        fresh.requestEmailChange(new ContactEmail("sam.new@example.com"), "idx:sam.new@example.com", Sha256.hex("change-token"), NOW);
        ReflectionTestUtils.setField(fresh, "id", 8L);
        when(accounts.findForUpdateById(8L)).thenReturn(Optional.of(fresh));

        service.confirmEmailChange(8L, "change-token");

        // tok-1 is the first token this service makes: the undo token, for the old address. Only its hash is kept on the account.
        verify(events).emailChanged(8L, new ContactEmail("sam@example.com"), new ContactEmail("sam.new@example.com"), "tok-1",
                NOW.plus(TravellerAccount.UNDO_LIFETIME));
        assertThat(fresh.undoEmailChangeIsValidFor(Sha256.hex("tok-1"), NOW)).isTrue();
    }
}
