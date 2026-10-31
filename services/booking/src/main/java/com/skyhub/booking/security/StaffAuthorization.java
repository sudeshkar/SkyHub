package com.skyhub.booking.security;

import com.skyhub.booking.domain.StaffRole;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Allows a request only for a valid <em>staff</em> token (scope {@code staff}) whose role passes the given test
 * (ADR-0017). The role comes from the signed token, so a request costs no database lookup. A booking token has
 * the wrong scope and no role, so it never passes. No token is 401 (from the resource server); a token that is
 * valid but not allowed is 403.
 */
public final class StaffAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    private static final String STAFF_AUTHORITY = "SCOPE_" + JwtStaffTokenIssuer.SCOPE;

    private final Predicate<StaffRole> allowed;

    private StaffAuthorization(Predicate<StaffRole> allowed) {
        this.allowed = allowed;
    }

    public static StaffAuthorization recordsDisruptions() {
        return new StaffAuthorization(StaffRole::mayRecordDisruptions);
    }

    public static StaffAuthorization viewsAffectedBookings() {
        return new StaffAuthorization(StaffRole::mayViewAffectedBookings);
    }

    public static StaffAuthorization rebooks() {
        return new StaffAuthorization(StaffRole::mayRebook);
    }

    public static StaffAuthorization verifiesBoardingPasses() {
        return new StaffAuthorization(StaffRole::mayVerifyBoardingPasses);
    }

    public static StaffAuthorization boardsPassengers() {
        return new StaffAuthorization(StaffRole::mayBoardPassengers);
    }

    public static StaffAuthorization createsStaff() {
        return new StaffAuthorization(StaffRole::mayCreateStaff);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        boolean granted = auth instanceof JwtAuthenticationToken jwt
                && jwt.isAuthenticated()
                && jwt.getAuthorities().stream().anyMatch(a -> STAFF_AUTHORITY.equals(a.getAuthority()))
                && roleOf(jwt) != null
                && allowed.test(roleOf(jwt));
        return new AuthorizationDecision(granted);
    }

    private static StaffRole roleOf(JwtAuthenticationToken jwt) {
        String claim = jwt.getToken().getClaimAsString(JwtStaffTokenIssuer.ROLE_CLAIM);
        try {
            return claim == null ? null : StaffRole.valueOf(claim);
        } catch (IllegalArgumentException unknownRole) {
            return null;
        }
    }
}
