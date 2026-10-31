package com.skyhub.booking.application;

import com.skyhub.booking.domain.InvalidBookingException;
import java.util.Locale;

/**
 * Which of a traveller's bookings "Your bookings" shows (ADR-0042). {@code UPCOMING} is a confirmed booking with a flight today or
 * later (UTC dates, as everywhere, ADR-0005); {@code PAST} is every other booking, including cancelled ones; {@code ALL} is both.
 */
public enum BookingFilter {
    ALL, UPCOMING, PAST;

    /** No filter, or an empty one, means all bookings. Anything that is not one of the three is refused, saying what is allowed. */
    public static BookingFilter fromWire(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "upcoming" -> UPCOMING;
            case "past" -> PAST;
            default -> throw new InvalidBookingException("Choose which bookings to show: all, upcoming or past");
        };
    }
}
