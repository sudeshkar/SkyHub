package com.skyhub.booking.application;

import com.skyhub.booking.domain.PnrSegment;
import java.util.List;

public record CreatePnrCommand(String contactEmail, List<PnrSegment> segments, List<PassengerCommand> passengers) {
}
