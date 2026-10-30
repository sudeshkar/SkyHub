package com.skyhub.booking.application.rebooking;

import java.util.Objects;
import java.util.Optional;

/** A traveller going to London is never moved to a flight to New York. If a route cannot be checked, the move is refused. */
public final class MustStayOnTheSameRoute implements RebookingRule {

    @Override
    public Optional<String> refusal(RebookingContext context) {
        var from = context.fromInfo();
        var to = context.toInfo();
        if (from.origin() == null || from.destination() == null || to.origin() == null || to.destination() == null) {
            return Optional.of("The route of one of the flights is not known, so the move cannot be checked");
        }
        if (Objects.equals(from.origin(), to.origin()) && Objects.equals(from.destination(), to.destination())) {
            return Optional.empty();
        }
        return Optional.of(context.to().flightNumber() + " flies " + to.origin() + " to " + to.destination()
                + ", not the booked route " + from.origin() + " to " + from.destination());
    }
}
