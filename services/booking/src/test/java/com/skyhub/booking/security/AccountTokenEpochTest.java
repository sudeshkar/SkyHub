package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AccountTokenEpochTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final SecretKey KEY = ManageTokenConfig.parseKey(Base64.getEncoder().encodeToString(new byte[32]));
    private final JwtAccountTokenIssuer issuer =
            new JwtAccountTokenIssuer(ManageTokenConfig.encoder(KEY), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(30));

    private Jwt decode(String token) {
        return ManageTokenConfig.decoder(KEY, Clock.fixed(NOW, ZoneOffset.UTC)).decode(token);
    }

    @Test
    void should_record_the_epoch_the_token_was_issued_under() {
        Jwt jwt = decode(issuer.issue(7L, 3).value());

        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.<Number>getClaim("epoch").intValue()).isEqualTo(3);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("account");
    }

    @Test
    void should_issue_under_epoch_zero_when_no_epoch_is_given() {
        assertThat(decode(issuer.issue(7L).value()).<Number>getClaim("epoch").intValue()).isZero();
    }
}
