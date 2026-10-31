package com.skyhub.booking.security;

import com.skyhub.booking.application.AccountToken;
import com.skyhub.booking.application.AccountTokenIssuer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Adapter: a short-lived JWT whose subject is the account id, with scope {@code account}. Same key as the other tokens, told
 * apart by scope (ADR-0033): a booking token has scope {@code manage}, a staff token {@code staff}, and none can stand in for another.
 */
public final class JwtAccountTokenIssuer implements AccountTokenIssuer {

    static final String SCOPE = "account";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration timeToLive;

    public JwtAccountTokenIssuer(JwtEncoder encoder, Clock clock, Duration timeToLive) {
        this.encoder = encoder;
        this.clock = clock;
        this.timeToLive = timeToLive;
    }

    @Override
    public AccountToken issue(long accountId, int epoch) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(timeToLive);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ManageTokenConfig.ISSUER)
                .subject(Long.toString(accountId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("scope", SCOPE)
                .claim("epoch", epoch)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AccountToken(value, expiresAt);
    }
}
