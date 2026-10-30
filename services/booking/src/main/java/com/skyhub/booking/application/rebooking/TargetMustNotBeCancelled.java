package com.skyhub.booking.application.rebooking;

import java.util.Optional;

public final class TargetMustNotBeCancelled implements RebookingRule {

    @Override
    public Optional<String> refusal(RebookingContext context) {
        if (context.toDisruption().filter(d -> d.isCancelled()).isPresent()) {
            return Optional.of(context.to().flightNumber() + " on " + context.to().departureDate() + " is cancelled");
        }
        return Optional.empty();
    }
}
