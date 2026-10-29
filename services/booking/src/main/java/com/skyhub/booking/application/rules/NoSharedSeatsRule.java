package com.skyhub.booking.application.rules;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.application.PassengerCommand;
import com.skyhub.booking.domain.InvalidBookingException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Two passengers in one booking cannot share a seat. O(passengers) per flight with a hash set. */
@Component
@Order(50)
public class NoSharedSeatsRule implements BookingRule {

    @Override
    public void check(CreatePnrCommand command) {
        for (int i = 0; i < command.segments().size(); i++) {
            Set<String> taken = new HashSet<>();
            for (PassengerCommand passenger : command.passengers()) {
                String seat = passenger.seats().get(i);
                if (!taken.add(seat)) {
                    throw new InvalidBookingException(
                            "Seat " + seat + " is selected twice on " + command.segments().get(i).flightNumber());
                }
            }
        }
    }
}
