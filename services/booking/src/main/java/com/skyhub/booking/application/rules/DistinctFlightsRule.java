package com.skyhub.booking.application.rules;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.domain.InvalidBookingException;
import java.util.HashSet;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class DistinctFlightsRule implements BookingRule {

    @Override
    public void check(CreatePnrCommand command) {
        if (new HashSet<>(command.segments()).size() != command.segments().size()) {
            throw new InvalidBookingException("The same flight appears twice in this booking");
        }
    }
}
