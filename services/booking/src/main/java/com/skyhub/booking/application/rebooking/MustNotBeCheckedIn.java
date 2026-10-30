package com.skyhub.booking.application.rebooking;

import java.util.Optional;

/** A checked-in flight has boarding passes for the old seats; moving it is a separate, later step (ADR-0023). */
public final class MustNotBeCheckedIn implements RebookingRule {

    @Override
    public Optional<String> refusal(RebookingContext context) {
        if (context.checkedIn()) {
            return Optional.of(context.from().flightNumber() + " is checked in, so it is not moved automatically");
        }
        return Optional.empty();
    }
}
