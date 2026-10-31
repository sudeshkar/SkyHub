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
    default List<BookingSummary> newestAfter(String emailIndex, BookingCursor after, int limit) {
        return newestAfter(emailIndex, BookingFilter.ALL, java.time.LocalDate.EPOCH, after, limit);
    }

    /**
     * The same, narrowed by {@code filter} (ADR-0042), where "upcoming" means a confirmed booking with a flight on or after {@code today}.
     * The order and the cursor are unchanged. {@code UPCOMING} and {@code PAST} have their own orders and are read with
     * {@link #upcomingSoonest} and {@link #pastMostRecent}. The cost is bounded by the traveller's own bookings, never by the system's.
     */
    List<BookingSummary> newestAfter(String emailIndex, BookingFilter filter, java.time.LocalDate today, BookingCursor after, int limit);

    /**
     * Confirmed bookings with a flight on or after {@code today}, soonest next flight first (ADR-0044), starting strictly after
     * {@code after} (the first page when null). A booking is positioned by its next flight date, the earliest that is today or later;
     * ties go to the reference. The cost grows with this traveller's own upcoming bookings, never with the system's.
     */
    List<BookingSummary> upcomingSoonest(String emailIndex, java.time.LocalDate today, UpcomingCursor after, int limit);

    /**
     * Every booking that is not upcoming (cancelled ones included), the one whose last flight was most recent first (ADR-0051),
     * starting strictly after {@code after} (the first page when null). A booking is positioned by the date of its last flight; ties go
     * to the higher reference. The cost grows with this traveller's own bookings, never with the system's.
     */
    List<BookingSummary> pastMostRecent(String emailIndex, java.time.LocalDate today, PastCursor after, int limit);

    /** Whether this booking was made with this address. False for an unknown reference. */
    boolean owns(String emailIndex, String locator);
}
