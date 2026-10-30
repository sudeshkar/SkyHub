package com.skyhub.booking.application.rebooking;

import java.util.Optional;

/** The replacement must not leave before the flight it replaces was due to: nobody is moved to something already gone. */
public final class MustNotLeaveEarlier implements RebookingRule {

    @Override
    public Optional<String> refusal(RebookingContext context) {
        if (context.toInfo().departure().isBefore(context.fromInfo().departure())) {
            return Optional.of(context.to().flightNumber() + " leaves before " + context.from().flightNumber()
                    + " was due to, so it is not a replacement");
        }
        return Optional.empty();
    }
}
