package com.skyhub.booking.application.rules;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.domain.InvalidBookingException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class OneSeatPerFlightRule implements BookingRule {

    @Override
    public void check(CreatePnrCommand command) {
        int flights = command.segments().size();
        if (command.passengers().stream().anyMatch(p -> p.seats().size() != flights)) {
            throw new InvalidBookingException("Each passenger needs one seat per flight");
        }
    }
}
