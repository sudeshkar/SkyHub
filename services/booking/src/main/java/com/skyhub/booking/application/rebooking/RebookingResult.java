package com.skyhub.booking.application.rebooking;

import com.skyhub.booking.domain.PnrSegment;
import java.util.List;

/** What a rebooking did. {@code seats} are in passenger order. {@code changed} is false when the booking was already there. */
public record RebookingResult(String locator, int segmentIndex, PnrSegment from, PnrSegment to, List<String> seats, boolean changed) {
}
