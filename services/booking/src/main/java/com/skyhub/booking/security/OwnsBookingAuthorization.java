package com.skyhub.booking.security;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Allows a request only when its token was issued for the booking named in the URL
 * ({@code /pnrs/{locator}/...}) and carries the "manage" scope. A valid token for booking A
 * is therefore useless against booking B. No token yields 401; a token for another booking, 403.
 */
public final class OwnsBookingAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    static final String MANAGE_AUTHORITY = "SCOPE_manage";

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        boolean owns = auth instanceof JwtAuthenticationToken jwt
                && jwt.isAuthenticated()
                && jwt.getAuthorities().stream().anyMatch(a -> MANAGE_AUTHORITY.equals(a.getAuthority()))
                && jwt.getToken().getSubject() != null
                && jwt.getToken().getSubject().equals(context.getVariables().get("locator"));
        return new AuthorizationDecision(owns);
    }
}
