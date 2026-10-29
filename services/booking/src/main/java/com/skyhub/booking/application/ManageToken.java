package com.skyhub.booking.application;

import java.time.Instant;

/** A signed, short-lived credential for managing one booking. Opaque to the application layer. */
public record ManageToken(String value, Instant expiresAt) {

    /** The token is a bearer credential: never let it reach a log line through toString(). */
    @Override
    public String toString() {
        return "ManageToken[expiresAt=" + expiresAt + "]";
    }
}
