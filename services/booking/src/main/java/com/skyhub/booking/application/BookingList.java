package com.skyhub.booking.application;

import com.skyhub.booking.application.view.BookingSummary;
import java.util.List;

/** One page of a traveller's bookings, newest first. {@code nextCursor} is null on the last page (ADR-0038). */
public record BookingList(List<BookingSummary> bookings, String nextCursor) {

    /** There are older bookings than the ones returned. */
    public boolean truncated() {
        return nextCursor != null;
    }
}
