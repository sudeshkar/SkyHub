package com.skyhub.booking.security;

import com.skyhub.booking.application.ManageToken;
import com.skyhub.booking.application.ManageTokenIssuer;
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
 * Adapter: signs a short-lived JWT whose subject is the booking locator. The token carries no
 * personal data (no name, no email), so a leaked token reveals nothing beyond what it unlocks, and
 * only for its own booking until it expires.
 */
public final class JwtManageTokenIssuer implements ManageTokenIssuer {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration timeToLive;

    public JwtManageTokenIssuer(JwtEncoder encoder, Clock clock, Duration timeToLive) {
        this.encoder = encoder;
        this.clock = clock;
        this.timeToLive = timeToLive;
    }

    @Override
    public ManageToken issue(String locator, int epoch) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(timeToLive);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ManageTokenConfig.ISSUER)
                .subject(locator)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("scope", "manage")
                .claim("epoch", epoch)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new ManageToken(value, expiresAt);
    }
}
