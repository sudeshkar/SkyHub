package com.skyhub.booking.domain;

import java.util.regex.Pattern;

/**
 * Value object for personal data. Validates on creation, knows its masked form, and never prints the
 * raw address: {@link #toString()} is masked, so a careless log line cannot leak it.
 * Stored encrypted (see persistence.ContactEmailConverter).
 */
public record ContactEmail(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_LENGTH = 254;

    public ContactEmail {
        value = value == null ? "" : value.strip();
        if (value.length() > MAX_LENGTH || !FORMAT.matcher(value).matches()) {
            throw new InvalidBookingException("Contact email is not valid");
        }
    }

    /** "priya@example.com" → "p****@example.com": enough to recognise, not enough to harvest. */
    public String masked() {
        int at = value.indexOf('@');
        String visible = at == 1 ? "" : value.substring(0, 1);
        return visible + "****" + value.substring(at);
    }

    @Override
    public String toString() {
        return masked();
    }
}
