package com.skyhub.booking.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.skyhub.booking.application.AccountMetrics;
import com.skyhub.booking.application.AccountSessions;
import com.skyhub.booking.application.BookingSessions;
import com.skyhub.booking.application.ManageMetrics;
import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.StaffTokenIssuer;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signing key and JWT plumbing for manage-booking tokens (ADR-0011). HS256 with a key that only
 * this service holds; it is separate from the personal-data key so that neither can stand in for the other.
 */
@Configuration(proxyBeanMethods = false)
public class ManageTokenConfig {

    static final String ISSUER = "https://skyhub.local/booking";
    private static final int KEY_BYTES = 32;
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** The service refuses to start without a proper key, rather than signing with a weak one. */
    static SecretKey parseKey(String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("skyhub.security.manage-token-key must be Base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "skyhub.security.manage-token-key must be a Base64 256-bit key (set the SKYHUB_MANAGE_TOKEN_KEY environment variable)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    static JwtEncoder encoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    static JwtDecoder decoder(SecretKey key, Clock clock) {
        return decoder(key, clock, jwt -> OAuth2TokenValidatorResult.success());
    }

    /** {@code extra} runs after the signature, expiry and issuer checks: here, whether an account session was ended (ADR-0041). */
    static JwtDecoder decoder(SecretKey key, Clock clock, OAuth2TokenValidator<Jwt> extra) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER), extra));
        return decoder;
    }

    @Bean
    SecretKey manageTokenKey(@Value("${skyhub.security.manage-token-key:}") String base64Key) {
        return parseKey(base64Key);
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey manageTokenKey) {
        return encoder(manageTokenKey);
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey manageTokenKey, Clock clock, AccountSessions sessions, AccountMetrics metrics,
                          BookingSessions bookingSessions, ManageMetrics manageMetrics) {
        // Each validator looks only at its own kind of token: an ended account session (ADR-0041), an ended booking session (ADR-0057).
        return decoder(manageTokenKey, clock, new DelegatingOAuth2TokenValidator<>(new RevokedSessionValidator(sessions, metrics),
                new RevokedBookingSessionValidator(bookingSessions, manageMetrics)));
    }

    @Bean
    StaffTokenIssuer staffTokenIssuer(JwtEncoder encoder, Clock clock,
                                      @Value("${skyhub.security.staff-token-ttl:PT30M}") Duration timeToLive) {
        return new JwtStaffTokenIssuer(encoder, clock, timeToLive);
    }

    @Bean
    AccountTokenIssuer accountTokenIssuer(JwtEncoder encoder, Clock clock,
                                          @Value("${skyhub.security.account-token-ttl:PT30M}") Duration timeToLive) {
        return new JwtAccountTokenIssuer(encoder, clock, timeToLive);
    }

    @Bean
    ManageTokenIssuer manageTokenIssuer(JwtEncoder encoder, Clock clock,
                                        @Value("${skyhub.security.manage-token-ttl:PT15M}") Duration timeToLive) {
        return new JwtManageTokenIssuer(encoder, clock, timeToLive);
    }
}
