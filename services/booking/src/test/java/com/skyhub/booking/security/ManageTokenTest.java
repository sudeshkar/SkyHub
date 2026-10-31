package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.skyhub.booking.application.ManageToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class ManageTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final SecretKey KEY = ManageTokenConfig.parseKey(Base64.getEncoder().encodeToString(new byte[32]));

    private final JwtManageTokenIssuer issuer = issuerAt(NOW);

    private static JwtManageTokenIssuer issuerAt(Instant now) {
        return new JwtManageTokenIssuer(ManageTokenConfig.encoder(KEY), Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(15));
    }

    private static JwtDecoder decoderAt(Instant now) {
        return ManageTokenConfig.decoder(KEY, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void should_issue_a_token_for_one_booking_that_expires_in_fifteen_minutes() {
        ManageToken token = issuer.issue("ABC234", 0);

        Jwt jwt = decoderAt(NOW.plusSeconds(60)).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo("ABC234");
        assertThat(jwt.getIssuer().toString()).isEqualTo(ManageTokenConfig.ISSUER);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void should_carry_the_epoch_it_was_issued_under_so_ending_the_sessions_can_refuse_it_later() {
        Jwt jwt = decoderAt(NOW).decode(issuer.issue("ABC234", 3).value());

        assertThat(jwt.<Number>getClaim("epoch").intValue()).isEqualTo(3);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("manage");
    }

    @Test
    void should_give_every_token_its_own_id() {
        assertThat(decoderAt(NOW).decode(issuer.issue("ABC234", 0).value()).getId())
                .isNotEqualTo(decoderAt(NOW).decode(issuer.issue("ABC234", 0).value()).getId());
    }

    @Test
    void should_reject_an_expired_token() {
        String token = issuer.issue("ABC234", 0).value();

        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> decoderAt(NOW.plus(Duration.ofMinutes(16))).decode(token));
    }

    @Test
    void should_reject_a_token_with_a_tampered_payload() {
        String[] parts = issuer.issue("ABC234", 0).value().split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("ABC234", "ZZZZZZ").getBytes());

        assertThatExceptionOfType(JwtException.class)
                .isThrownBy(() -> decoderAt(NOW).decode(parts[0] + "." + forged + "." + parts[2]));
    }

    @Test
    void should_reject_a_token_signed_with_another_key() {
        byte[] other = new byte[32];
        other[0] = 7;
        SecretKey otherKey = new SecretKeySpec(other, "HmacSHA256");
        var foreign = new JwtManageTokenIssuer(ManageTokenConfig.encoder(otherKey), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15));

        assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoderAt(NOW).decode(foreign.issue("ABC234", 0).value()));
    }

    @Test
    void should_reject_an_unsigned_token() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"ABC234\",\"iss\":\"" + ManageTokenConfig.ISSUER + "\",\"exp\":" + NOW.plusSeconds(600).getEpochSecond() + "}").getBytes());

        assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoderAt(NOW).decode(header + "." + payload + "."));
    }

    @Test
    void should_never_print_the_token() {
        ManageToken token = issuer.issue("ABC234", 0);

        assertThat(token.toString()).doesNotContain(token.value()).doesNotContain("eyJ");
    }

    @Test
    void should_refuse_a_key_that_is_not_256_bits_or_not_base64() {
        assertThatIllegalStateException().isThrownBy(() -> ManageTokenConfig.parseKey(""));
        assertThatIllegalStateException().isThrownBy(() -> ManageTokenConfig.parseKey("not base64!"));
        assertThatIllegalStateException()
                .isThrownBy(() -> ManageTokenConfig.parseKey(Base64.getEncoder().encodeToString(new byte[16])))
                .withMessageContaining("SKYHUB_MANAGE_TOKEN_KEY");
    }
}
