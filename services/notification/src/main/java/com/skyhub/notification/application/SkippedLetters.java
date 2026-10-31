package com.skyhub.notification.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Port: the parked messages a person has decided the replay must leave out (ADR-0037), by where they sit on the dead-letter topic.
 * The persistence layer implements it, so neither the replay nor the operator's endpoint knows how the list is stored.
 */
public interface SkippedLetters {

    /** A message to leave out, and why. Never holds a message body. */
    record Skip(int partition, long offset, String reason, Instant skippedAt) {
    }

    /** Lists a message to leave out. Listing it again changes nothing. */
    void skip(int partition, long offset, String reason, Instant at);

    /** Takes a message off the list. @return false if it was not listed */
    boolean unskip(int partition, long offset);

    /** The offsets in {@code fromOffset..toOffset} (both included) of this partition that are listed. One lookup, however many. */
    Set<Long> skippedIn(int partition, long fromOffset, long toOffset);

    /** Removes entries the replay has passed: below the committed offset they are never looked at again. */
    void forget(int partition, Collection<Long> offsets);

    List<Skip> all();
}
