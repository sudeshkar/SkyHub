package com.skyhub.booking.application;

import java.time.Instant;

/** A signed, short-lived credential for a logged-in member of staff. Opaque to the application layer. */
public record StaffToken(String value, Instant expiresAt) {

    /** A bearer credential: never let it reach a log line through toString(). */
    @Override
    public String toString() {
        return "StaffToken[expiresAt=" + expiresAt + "]";
    }
}
