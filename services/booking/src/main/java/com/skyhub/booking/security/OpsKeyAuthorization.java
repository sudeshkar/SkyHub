package com.skyhub.booking.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Allows a request only when its {@code X-Ops-Key} header equals the configured operations key (ADR-0014).
 * Stands in for staff login. With no key configured the endpoint is closed to everyone. The comparison is
 * constant-time, and over digests, so neither the key's length nor how many leading characters matched leaks.
 */
public final class OpsKeyAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    static final String HEADER = "X-Ops-Key";
    private static final int MIN_KEY_LENGTH = 32;

    private final byte[] expectedDigest; // null when disabled

    public OpsKeyAuthorization(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            this.expectedDigest = null;
            return;
        }
        if (configuredKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("skyhub.security.ops-key must be at least " + MIN_KEY_LENGTH
                    + " characters (set the SKYHUB_OPS_KEY environment variable)");
        }
        this.expectedDigest = sha256(configuredKey);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        String given = context.getRequest().getHeader(HEADER);
        boolean granted = expectedDigest != null && given != null && MessageDigest.isEqual(expectedDigest, sha256(given));
        return new AuthorizationDecision(granted);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
