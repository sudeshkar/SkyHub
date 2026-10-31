package com.skyhub.booking.application;

import com.skyhub.booking.domain.InvalidBookingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A place in a traveller's list of past trips, most recently flown first (ADR-0051): the date of the last flight on the last
 * booking seen and its reference. The next page starts strictly after it, in the order (last flight date descending, reference
 * descending), which is total because the reference is unique. It is a third kind of position, refused for the other two and by
 * them, never guessed at. It is only ever a bound inside the account's own bookings, so a forged one cannot reach anyone else's.
 */
public record PastCursor(LocalDate lastFlight, String locator) {

    private static final Pattern LOCATOR = Pattern.compile("^[A-Z0-9]{6}$");
    private static final String KIND = "p";
    private static final int MAX_LENGTH = 200;
    private static final String INVALID = "That page of bookings is not valid";

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((KIND + "|" + lastFlight + "|" + locator).getBytes(StandardCharsets.UTF_8));
    }

    /** No cursor (or an empty one) means the first page. */
    public static PastCursor decodeOrNull(String cursor) {
        return cursor == null || cursor.isBlank() ? null : decode(cursor);
    }

    public static PastCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
            throw new InvalidBookingException(INVALID);
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !KIND.equals(parts[0]) || !LOCATOR.matcher(parts[2]).matches()) {
                throw new InvalidBookingException(INVALID);
            }
            return new PastCursor(LocalDate.parse(parts[1]), parts[2]);
        } catch (IllegalArgumentException | DateTimeParseException notOurs) {
            throw new InvalidBookingException(INVALID);
        }
    }
}
