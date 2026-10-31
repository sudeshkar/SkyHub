package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.InvalidStaffAccountException;
import com.skyhub.booking.domain.StaffAccount;
import com.skyhub.booking.domain.StaffRole;
import com.skyhub.booking.persistence.StaffAccountRepository;
import com.skyhub.booking.security.BCryptPasswordHasher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final String PASSWORD = "a-long-enough-passphrase";
    private static final StaffToken TOKEN = new StaffToken("jwt", NOW.plus(Duration.ofMinutes(30)));

    @Mock
    private StaffAccountRepository accounts;

    @Mock
    private StaffTokenIssuer tokens;

    private final PasswordHasher encoder = new BCryptPasswordHasher(4); // cheap for tests; production uses the default
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private StaffService service;
    private StaffAccount priya;

    @BeforeEach
    void setUp() {
        service = new StaffService(accounts, encoder, new PasswordGate(encoder), tokens, Clock.fixed(NOW, ZoneOffset.UTC),
                new StaffMetrics(registry), TransactionOperations.withoutTransaction());
        priya = StaffAccount.create("priya.ops", encoder.hash(PASSWORD), StaffRole.OPS, NOW);
        when(accounts.findForUpdateByUsername("priya.ops")).thenReturn(Optional.of(priya));
        when(tokens.issue("priya.ops", StaffRole.OPS)).thenReturn(TOKEN);
    }

    private double logins(String outcome) {
        return registry.get("skyhub.staff.logins").tag("outcome", outcome).counter().count();
    }

    // ---- login ---------------------------------------------------------------------------------------------------

    @Test
    void should_log_in_with_the_right_password_and_return_a_token_with_the_role() {
        StaffSession session = service.login("priya.ops", PASSWORD);

        assertThat(session.username()).isEqualTo("priya.ops");
        assertThat(session.role()).isEqualTo(StaffRole.OPS);
        assertThat(session.token()).isSameAs(TOKEN);
        assertThat(logins("success")).isEqualTo(1);
    }

    @Test
    void should_accept_the_username_in_any_case() {
        assertThat(service.login("  Priya.Ops ", PASSWORD).username()).isEqualTo("priya.ops");
    }

    @Test
    void should_fail_the_same_way_for_a_wrong_password_and_an_unknown_user() {
        when(accounts.findForUpdateByUsername("nobody")).thenReturn(Optional.empty());

        StaffLoginFailedException wrong = catchLoginFailure("priya.ops", "not-the-password");
        StaffLoginFailedException unknown = catchLoginFailure("nobody", PASSWORD);

        assertThat(wrong).hasMessage(unknown.getMessage());
        verify(tokens, never()).issue(any(), any());
        assertThat(logins("failed")).isEqualTo(2);
    }

    @Test
    void should_still_check_a_password_for_an_unknown_user_so_timing_does_not_give_the_name_away() {
        PasswordHasher watched = spy(encoder);
        StaffService timed = new StaffService(accounts, watched, new PasswordGate(watched), tokens, Clock.fixed(NOW, ZoneOffset.UTC),
                new StaffMetrics(new SimpleMeterRegistry()), TransactionOperations.withoutTransaction());
        when(accounts.findForUpdateByUsername("nobody")).thenReturn(Optional.empty());

        assertThatExceptionOfType(StaffLoginFailedException.class).isThrownBy(() -> timed.login("nobody", PASSWORD));

        verify(watched).matches(any(), any());
    }

    @Test
    void should_save_the_failure_count_and_lock_the_account_after_five_wrong_passwords() {
        for (int i = 0; i < 5; i++) {
            catchLoginFailure("priya.ops", "wrong-" + i);
        }

        assertThat(priya.isLockedAt(NOW)).isTrue();
        verify(accounts, org.mockito.Mockito.times(5)).save(priya);
        assertThat(logins("failed")).isEqualTo(5);
    }

    @Test
    void should_refuse_the_right_password_while_locked_and_say_nothing_different() {
        for (int i = 0; i < 5; i++) {
            catchLoginFailure("priya.ops", "wrong-" + i);
        }

        StaffLoginFailedException locked = catchLoginFailure("priya.ops", PASSWORD);

        assertThat(locked).hasMessage(catchLoginFailure("priya.ops", "wrong-again").getMessage());
        assertThat(logins("locked")).isEqualTo(2);
        verify(tokens, never()).issue(any(), any());
    }

    @Test
    void should_clear_earlier_mistakes_on_a_successful_login() {
        catchLoginFailure("priya.ops", "wrong");

        service.login("priya.ops", PASSWORD);
        for (int i = 0; i < 4; i++) {
            catchLoginFailure("priya.ops", "wrong");
        }

        assertThat(priya.isLockedAt(NOW)).isFalse();
    }

    private StaffLoginFailedException catchLoginFailure(String username, String password) {
        try {
            service.login(username, password);
        } catch (StaffLoginFailedException e) {
            return e;
        }
        throw new AssertionError("login should have failed");
    }

    // ---- creating accounts ---------------------------------------------------------------------------------------

    @Test
    void should_create_an_account_storing_only_a_hash() {
        when(accounts.existsByUsername("sam.ops")).thenReturn(false);

        StaffAccountView created = service.create("admin", new CreateStaffCommand("Sam.Ops", PASSWORD, StaffRole.OPS));

        assertThat(created.username()).isEqualTo("sam.ops");
        assertThat(created.role()).isEqualTo(StaffRole.OPS);
        org.mockito.ArgumentCaptor<StaffAccount> saved = org.mockito.ArgumentCaptor.forClass(StaffAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void should_refuse_a_taken_username() {
        when(accounts.existsByUsername("priya.ops")).thenReturn(true);

        assertThatExceptionOfType(StaffUsernameTakenException.class)
                .isThrownBy(() -> service.create("admin", new CreateStaffCommand("priya.ops", PASSWORD, StaffRole.OPS)));
        verify(accounts, never()).save(any());
    }

    @Test
    void should_refuse_a_short_password() {
        assertThatExceptionOfType(InvalidStaffAccountException.class)
                .isThrownBy(() -> service.create("admin", new CreateStaffCommand("sam.ops", "short", StaffRole.OPS)));
        verify(accounts, never()).save(any());
    }

    // ---- the first administrator ---------------------------------------------------------------------------------

    @Test
    void should_create_the_first_admin_only_when_there_are_no_accounts() {
        when(accounts.count()).thenReturn(0L);

        assertThat(service.ensureFirstAdmin("root.admin", PASSWORD)).isTrue();

        org.mockito.ArgumentCaptor<StaffAccount> saved = org.mockito.ArgumentCaptor.forClass(StaffAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(StaffRole.ADMIN);
    }

    @Test
    void should_ignore_the_bootstrap_settings_once_any_account_exists_or_when_they_are_missing() {
        when(accounts.count()).thenReturn(1L);

        assertThat(service.ensureFirstAdmin("root.admin", PASSWORD)).isFalse();
        assertThat(service.ensureFirstAdmin("", "")).isFalse();
        assertThat(service.ensureFirstAdmin(null, null)).isFalse();
        verify(accounts, never()).save(any());
    }
}
