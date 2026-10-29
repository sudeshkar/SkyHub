package com.skyhub.booking.domain;

import java.util.UUID;

/** Random (version 4) UUIDs from a secure source: 122 bits, not guessable. */
public final class UuidHoldIdGenerator implements HoldIdGenerator {

    @Override
    public String next() {
        return UUID.randomUUID().toString();
    }
}
