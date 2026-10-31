package com.skyhub.booking.security;

import com.skyhub.booking.application.BookingSessions;
import com.skyhub.booking.application.ManageMetrics;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Refuses a booking token once the booking's sessions were ended (ADR-0057): the token's {@code epoch} claim must still be the booking's
 * current epoch. A token without the claim counts as epoch 0, so tokens issued before this existed stay valid until the first sign-out.
 * Only booking tokens are checked; account and staff tokens have their own rules. A refusal is an ordinary invalid token, a 401, which
 * the website already treats as an ended session.
 */
public final class RevokedBookingSessionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ENDED = new OAuth2Error("invalid_token", "The session has ended", null);

    private final BookingSessions sessions;
    private final ManageMetrics metrics;

    public RevokedBookingSessionValidator(BookingSessions sessions, ManageMetrics metrics) {
        this.sessions = sessions;
        this.metrics = metrics;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!"manage".equals(jwt.getClaimAsString("scope"))) {
            return OAuth2TokenValidatorResult.success();
        }
        Object claim = jwt.getClaim("epoch");
        if (claim != null && !(claim instanceof Number)) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        int epoch = claim == null ? 0 : ((Number) claim).intValue();
        if (jwt.getSubject() != null && sessions.accepts(jwt.getSubject(), epoch)) {
            return OAuth2TokenValidatorResult.success();
        }
        metrics.record(ManageMetrics.Action.SESSION_REVOKED);
        return OAuth2TokenValidatorResult.failure(ENDED);
    }
}
