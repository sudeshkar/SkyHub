package com.skyhub.booking.application;

import com.skyhub.booking.domain.InvalidBookingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A place in a traveller's list of bookings, newest first (ADR-0038): the creation time and reference of the last booking seen.
 * The next page starts strictly after it, in the order (created time, reference), which is total because the reference is unique.
 * It is an opaque string to callers. It is only ever used as a lower bound inside the account's own bookings, so a forged one can
 * move within them but never reach anyone else's; anything that is not a time and a reference is refused.
 */
public record BookingCursor(Instant createdAt, String locator) {

    private static final Pattern LOCATOR = Pattern.compile("^[A-Z0-9]{6}$");
    private static final int MAX_LENGTH = 200;
    private static final String INVALID = "That page of bookings is not valid";

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((createdAt + "|" + locator).getBytes(StandardCharsets.UTF_8));
    }

    /** No cursor (or an empty one) means the first page. */
    public static BookingCursor decodeOrNull(String cursor) {
        return cursor == null || cursor.isBlank() ? null : decode(cursor);
    }

    public static BookingCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_LENGTH) {
            throw new InvalidBookingException(INVALID);
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2 || !LOCATOR.matcher(parts[1]).matches()) {
                throw new InvalidBookingException(INVALID);
            }
            return new BookingCursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeParseException notOurs) {
            throw new InvalidBookingException(INVALID);
        }
    }
}
