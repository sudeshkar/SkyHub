package com.skyhub.booking.application.rebooking;

import java.util.Optional;

/**
 * A passenger who has boarded is on the aircraft, so their booking cannot be moved to another flight (ADR-0069). A booking that is only
 * checked in can be: the move checks the party out in the same step, and they check in again for the new flight. This replaces the rule
 * that refused every checked-in booking (ADR-0023).
 */
public final class MustNotHaveBoarded implements RebookingRule {

    @Override
    public Optional<String> refusal(RebookingContext context) {
        if (context.boarded()) {
            return Optional.of("A passenger has already boarded " + context.from().flightNumber() + ", so the booking is not moved");
        }
        return Optional.empty();
    }
}
