package com.skyhub.booking.application.rules;

import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.domain.InvalidBookingException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class SegmentCountRule implements BookingRule {

    static final int MAX_SEGMENTS = 4;

    @Override
    public void check(CreatePnrCommand command) {
        int count = command.segments().size();
        if (count < 1 || count > MAX_SEGMENTS) {
            throw new InvalidBookingException("A booking needs between 1 and " + MAX_SEGMENTS + " flights");
        }
    }
}
