package com.skyhub.booking.application;

import com.skyhub.booking.application.view.BookingSummary;
import java.util.List;

/** The newest bookings of a traveller; {@code truncated} says there are older ones that were not returned. */
public record BookingList(List<BookingSummary> bookings, boolean truncated) {
}
