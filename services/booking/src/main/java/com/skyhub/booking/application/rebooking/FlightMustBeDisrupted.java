package com.skyhub.booking.application.rebooking;

import java.util.Optional;

/** Staff can move bookings off a flight that has a recorded delay or cancellation, and off no other (ADR-0023). */
public final class FlightMustBeDisrupted implements RebookingRule {

    @Override
    public Optional<String> refusal(RebookingContext context) {
        if (context.fromDisruption().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(context.from().flightNumber() + " on " + context.from().departureDate()
                + " has no recorded delay or cancellation, so its bookings are not moved");
    }
}
