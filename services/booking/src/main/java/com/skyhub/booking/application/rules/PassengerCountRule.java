package com.skyhub.booking.application.rules;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.domain.InvalidBookingException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class PassengerCountRule implements BookingRule {

    static final int MAX_PASSENGERS = 9;

    @Override
    public void check(CreatePnrCommand command) {
        int count = command.passengers().size();
        if (count < 1 || count > MAX_PASSENGERS) {
            throw new InvalidBookingException("A booking needs between 1 and " + MAX_PASSENGERS + " passengers");
        }
    }
}
