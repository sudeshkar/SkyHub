package com.skyhub.booking;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.application.PassengerCommand;
import com.skyhub.booking.domain.PnrSegment;
import java.util.List;

/** Booking commands for tests: paid with the approved test card, no hold, no expected price. */
public final class TestCommands {

    public static final String APPROVED_TOKEN = "tok_visa";

    private TestCommands() {
    }

    public static CreatePnrCommand pnr(String email, List<PnrSegment> segments, List<PassengerCommand> passengers) {
        return new CreatePnrCommand(email, segments, passengers, null, APPROVED_TOKEN, null);
    }

    public static CreatePnrCommand pnr(String email, List<PnrSegment> segments, List<PassengerCommand> passengers,
                                       String holdId) {
        return new CreatePnrCommand(email, segments, passengers, holdId, APPROVED_TOKEN, null);
    }
}
