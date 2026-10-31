package com.skyhub.booking.application;

import com.skyhub.booking.application.view.BookingSummary;
import java.util.List;

/**
 * Port: bookings found by the keyed hash of their contact address (ADR-0034), never by the address itself. The persistence layer
 * implements it, so the use case does not know how bookings are stored or read.
 */
public interface BookingsByAddress {

    /**
     * At most {@code limit} bookings, newest first, starting strictly after {@code after} (the first page when null). Cost depends
     * on {@code limit}, not on how many bookings exist or how far back the page is (ADR-0038).
     */
    List<BookingSummary> newestAfter(String emailIndex, BookingCursor after, int limit);

    /** Whether this booking was made with this address. False for an unknown reference. */
    boolean owns(String emailIndex, String locator);
}
