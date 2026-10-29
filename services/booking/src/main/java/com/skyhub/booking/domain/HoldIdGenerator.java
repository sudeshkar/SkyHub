package com.skyhub.booking.domain;

/** Produces hold ids. Knowing one is the permission to use that hold, so they must be unguessable. */
@FunctionalInterface
public interface HoldIdGenerator {

    String next();
}
