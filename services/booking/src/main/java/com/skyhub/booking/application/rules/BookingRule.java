package com.skyhub.booking.application.rules;

import com.skyhub.booking.application.CreatePnrCommand;

/**
 * One business rule for a booking request. New rules are new classes: Spring collects every
 * {@code BookingRule} bean, so adding a rule never edits existing code (open/closed principle).
 * Implementations throw {@link com.skyhub.booking.domain.InvalidBookingException} when broken.
 */
public interface BookingRule {

    void check(CreatePnrCommand command);
}
