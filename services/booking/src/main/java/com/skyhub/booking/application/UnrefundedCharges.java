package com.skyhub.booking.application;

import com.skyhub.booking.domain.Money;
import java.time.Instant;

/** Port: a durable note that a charge was taken for a booking that failed and has not been given back (ADR-0030). */
public interface UnrefundedCharges {

    /** Safe to repeat for the same charge. */
    void record(String chargeId, Money amount, String reason, Instant at);
}
