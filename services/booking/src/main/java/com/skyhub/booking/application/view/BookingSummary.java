package com.skyhub.booking.application.view;

import com.skyhub.booking.application.view.PnrView.SegmentView;
import com.skyhub.booking.domain.PnrStatus;
import java.time.Instant;
import java.util.List;

/**
 * One line of "my bookings". Deliberately holds no names, seats or addresses: a list of many bookings is shown more often and
 * more widely than one booking, and opening one gives the rest.
 */
public record BookingSummary(String locator, PnrStatus status, List<SegmentView> segments, int passengerCount, Instant createdAt) {
}
