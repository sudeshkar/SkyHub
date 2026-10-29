package com.skyhub.booking.application;

import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrSegment;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Business rules for a booking request, separate from the booking workflow (single responsibility).
 * The API contract already checks formats; these are the rules a schema cannot express.
 */
@Component
public class CreatePnrValidator {

    static final int MAX_SEGMENTS = 4;
    static final int MAX_PASSENGERS = 9;

    public void validate(CreatePnrCommand command) {
        List<PnrSegment> segments = command.segments();
        List<PassengerCommand> passengers = command.passengers();
        if (segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw new InvalidBookingException("A booking needs between 1 and " + MAX_SEGMENTS + " flights");
        }
        if (passengers.isEmpty() || passengers.size() > MAX_PASSENGERS) {
            throw new InvalidBookingException("A booking needs between 1 and " + MAX_PASSENGERS + " passengers");
        }
        if (new HashSet<>(segments).size() != segments.size()) {
            throw new InvalidBookingException("The same flight appears twice in this booking");
        }
        for (PassengerCommand passenger : passengers) {
            if (passenger.seats().size() != segments.size()) {
                throw new InvalidBookingException("Each passenger needs one seat per flight");
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            Set<String> taken = new HashSet<>();
            for (PassengerCommand passenger : passengers) {
                String seat = passenger.seats().get(i);
                if (!taken.add(seat)) {
                    throw new InvalidBookingException(
                            "Seat " + seat + " is selected twice on " + segments.get(i).flightNumber());
                }
            }
        }
    }
}
