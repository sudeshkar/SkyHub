package com.skyhub.booking.security;

import com.skyhub.booking.application.StaffToken;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.domain.StaffRole;
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
 * Adapter: signs a short-lived JWT whose subject is the username, with scope {@code staff} and the role as a claim.
 * Same key as manage tokens, told apart by scope (ADR-0017): a booking token has scope {@code manage} and no role.
 */
public final class JwtStaffTokenIssuer implements StaffTokenIssuer {

    static final String SCOPE = "staff";
    static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration timeToLive;

    public JwtStaffTokenIssuer(JwtEncoder encoder, Clock clock, Duration timeToLive) {
        this.encoder = encoder;
        this.clock = clock;
        this.timeToLive = timeToLive;
    }

    @Override
    public StaffToken issue(String username, StaffRole role) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(timeToLive);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ManageTokenConfig.ISSUER)
                .subject(username)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("scope", SCOPE)
                .claim(ROLE_CLAIM, role.name())
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new StaffToken(value, expiresAt);
    }
}
