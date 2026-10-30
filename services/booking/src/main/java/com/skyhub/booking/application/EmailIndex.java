package com.skyhub.booking.application;

/** Port: a stable, non-reversible handle for an email address, for looking things up by address (ADR-0033). */
public interface EmailIndex {

    /** Same address (ignoring case and surrounding spaces), same handle. */
    String of(String email);
}
