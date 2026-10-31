package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.BookingSessions;
import com.skyhub.booking.application.ManageMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** A booking token is valid only while the epoch it carries is the booking's current one (ADR-0057). */
class RevokedBookingSessionValidatorTest {

    private final BookingSessions sessions = mock(BookingSessions.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RevokedBookingSessionValidator validator = new RevokedBookingSessionValidator(sessions, new ManageMetrics(registry));

    private static Jwt.Builder token(String scope, String subject) {
        return Jwt.withTokenValue("t").header("alg", "HS256").issuedAt(Instant.parse("2026-11-02T10:00:00Z"))
                .expiresAt(Instant.parse("2026-11-02T10:15:00Z")).subject(subject).claim("scope", scope);
    }

    private double revoked() {
        var counter = registry.find("skyhub.manage.actions").tag("action", "session_revoked").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_accept_a_booking_token_whose_epoch_is_the_current_one() {
        when(sessions.accepts("ABC234", 2)).thenReturn(true);

        assertThat(validator.validate(token("manage", "ABC234").claim("epoch", 2).build()).hasErrors()).isFalse();
        assertThat(revoked()).isZero();
    }

    @Test
    void should_refuse_a_booking_token_from_before_the_sessions_were_ended() {
        when(sessions.accepts("ABC234", 1)).thenReturn(false);

        var result = validator.validate(token("manage", "ABC234").claim("epoch", 1).build());

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(error -> error.getErrorCode()).containsExactly("invalid_token");
        assertThat(revoked()).isEqualTo(1);
    }

    @Test
    void should_count_a_token_without_the_claim_as_epoch_zero_so_tokens_from_before_this_change_still_work() {
        when(sessions.accepts("ABC234", 0)).thenReturn(true);

        assertThat(validator.validate(token("manage", "ABC234").build()).hasErrors()).isFalse();
        verify(sessions).accepts("ABC234", 0);
    }

    @Test
    void should_refuse_an_epoch_that_is_not_a_number() {
        assertThat(validator.validate(token("manage", "ABC234").claim("epoch", "one").build()).hasErrors()).isTrue();
        verifyNoInteractions(sessions);
    }

    @Test
    void should_leave_every_other_kind_of_token_alone() {
        assertThat(validator.validate(token("account", "7").claim("epoch", 5).build()).hasErrors()).isFalse();
        assertThat(validator.validate(token("staff", "priya.ops").build()).hasErrors()).isFalse();

        verifyNoInteractions(sessions);
    }
}
