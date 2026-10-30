package com.skyhub.booking.application;

import java.time.Instant;
import java.util.List;

/**
 * Something that can owe a refund (ADR-0030). The retry job knows nothing about where refunds come from: a new kind of owed
 * refund is a new implementation of this interface, discovered by Spring, with no change to the job.
 */
public interface RefundSource {

    /** {@code attempt} is the number of this try, counting from 1. */
    record Pending(long id, String chargeId, int attempt) {
    }

    /** Tag for metrics and logs. */
    String name();

    /**
     * Takes up to {@code limit} refunds whose time has come and schedules each one's next attempt in advance, so another
     * instance (or the next sweep) does not take the same ones. Rows another instance holds are skipped, not waited for.
     * Refunds that have used all their attempts are not returned.
     */
    List<Pending> claimDue(Instant now, int limit);

    /** The provider confirmed the refund. Safe to call for a refund that was settled in the meantime. */
    void refunded(Pending pending, Instant now);
}
