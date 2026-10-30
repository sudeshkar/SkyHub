package com.skyhub.booking.application;

import com.skyhub.booking.domain.PnrSegment;
import java.util.List;

/**
 * One active booking on a disrupted flight, with only what re-accommodation needs (ADR-0022): the reference, how
 * many seats it holds, which of its flights is the disrupted one, and its whole route. There are no names and no
 * contact details, so this can be handed to tools and models that must not see personal data.
 */
public record AffectedBooking(String locator, int passengerCount, int segmentIndex, List<PnrSegment> segments) {
}
