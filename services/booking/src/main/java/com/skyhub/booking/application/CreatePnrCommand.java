package com.skyhub.booking.application;

import com.skyhub.booking.domain.PnrSegment;
import java.util.List;

/** {@code holdId} is optional: with it the seats were held earlier, without it they are booked directly. */
public record CreatePnrCommand(String contactEmail, List<PnrSegment> segments, List<PassengerCommand> passengers,
                               String holdId) {

    public CreatePnrCommand(String contactEmail, List<PnrSegment> segments, List<PassengerCommand> passengers) {
        this(contactEmail, segments, passengers, null);
    }
}
