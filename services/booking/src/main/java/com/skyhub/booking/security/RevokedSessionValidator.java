package com.skyhub.booking.security;

import com.skyhub.booking.application.AccountMetrics;
import com.skyhub.booking.application.AccountSessions;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Refuses an account token once the account's credentials have changed (ADR-0041): the token's {@code epoch} claim must still be the
 * account's current epoch. A token without the claim counts as epoch 0, so tokens issued before this existed stay valid until the
 * first change. Only account tokens are checked: a booking token or a staff token is a separate credential and never asks the account.
 * A refusal is an ordinary invalid token, so it is a 401, which the website already treats as an ended session.
 */
public final class RevokedSessionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ENDED = new OAuth2Error("invalid_token", "The session has ended", null);

    private final AccountSessions sessions;
    private final AccountMetrics metrics;

    public RevokedSessionValidator(AccountSessions sessions, AccountMetrics metrics) {
        this.sessions = sessions;
        this.metrics = metrics;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!JwtAccountTokenIssuer.SCOPE.equals(jwt.getClaimAsString("scope"))) {
            return OAuth2TokenValidatorResult.success();
        }
        long accountId;
        try {
            accountId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException notAnAccount) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        Object claim = jwt.getClaim("epoch");
        if (claim != null && !(claim instanceof Number)) {
            return OAuth2TokenValidatorResult.failure(ENDED);
        }
        int epoch = claim == null ? 0 : ((Number) claim).intValue();
        if (sessions.accepts(accountId, epoch)) {
            return OAuth2TokenValidatorResult.success();
        }
        metrics.session("revoked");
        return OAuth2TokenValidatorResult.failure(ENDED);
    }
}
