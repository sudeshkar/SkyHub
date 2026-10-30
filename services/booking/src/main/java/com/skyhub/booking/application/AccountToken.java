package com.skyhub.booking.application;

import java.time.Instant;

/** A signed, short-lived credential for a signed-in traveller. */
public record AccountToken(String value, Instant expiresAt) {

    @Override
    public String toString() {
        return "AccountToken[expiresAt=" + expiresAt + "]";
    }
}
