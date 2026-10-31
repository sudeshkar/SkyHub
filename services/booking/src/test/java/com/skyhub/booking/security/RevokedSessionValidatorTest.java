package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.AccountMetrics;
import com.skyhub.booking.application.AccountSessions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** An account token is valid only while the epoch it carries is the account's current one (ADR-0041). */
class RevokedSessionValidatorTest {

    private final AccountSessions sessions = mock(AccountSessions.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RevokedSessionValidator validator = new RevokedSessionValidator(sessions, new AccountMetrics(registry));

    private static Jwt.Builder token(String scope, String subject) {
        return Jwt.withTokenValue("t").header("alg", "HS256").issuedAt(Instant.parse("2026-11-02T10:00:00Z"))
                .expiresAt(Instant.parse("2026-11-02T10:30:00Z")).subject(subject).claim("scope", scope);
    }

    private double revoked() {
        var counter = registry.find("skyhub.account.sessions").tag("outcome", "revoked").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_accept_an_account_token_whose_epoch_is_the_current_one() {
        when(sessions.accepts(7L, 2)).thenReturn(true);

        assertThat(validator.validate(token("account", "7").claim("epoch", 2).build()).hasErrors()).isFalse();
        assertThat(revoked()).isZero();
    }

    @Test
    void should_refuse_an_account_token_from_before_the_credentials_changed() {
        when(sessions.accepts(7L, 1)).thenReturn(false);

        var result = validator.validate(token("account", "7").claim("epoch", 1).build());

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(error -> error.getErrorCode()).containsExactly("invalid_token");
        assertThat(revoked()).isEqualTo(1);
    }

    @Test
    void should_count_a_token_without_the_claim_as_epoch_zero_so_tokens_from_before_this_change_still_work() {
        when(sessions.accepts(7L, 0)).thenReturn(true);

        assertThat(validator.validate(token("account", "7").build()).hasErrors()).isFalse();
        verify(sessions).accepts(7L, 0);
    }

    @Test
    void should_refuse_a_token_whose_epoch_or_subject_is_not_a_number_without_asking_anyone() {
        assertThat(validator.validate(token("account", "7").claim("epoch", "two").build()).hasErrors()).isTrue();
        assertThat(validator.validate(token("account", "not-a-number").claim("epoch", 0).build()).hasErrors()).isTrue();

        verifyNoInteractions(sessions);
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() {
        when(sessions.accepts(99L, 0)).thenReturn(false);

        assertThat(validator.validate(token("account", "99").build()).hasErrors()).isTrue();
    }

    @Test
    void should_never_ask_about_a_booking_or_staff_token_which_are_other_credentials() {
        assertThat(validator.validate(token("manage", "ABC234").build()).hasErrors()).isFalse();
        assertThat(validator.validate(token("staff", "priya.ops").build()).hasErrors()).isFalse();

        verifyNoInteractions(sessions);
    }
}
