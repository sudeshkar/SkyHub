package com.skyhub.booking.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text that is signed and scanned at the gate (ADR-0013): one place writes it and one place reads it back (ADR-0056), so what
 * check-in issues and what the gate asks about cannot drift apart. The name comes first and may hold spaces, so it is read from
 * the right: everything before the last five fields is the name.
 */
public final class BoardingPassPayload {

    private static final Pattern READ = Pattern.compile("^M1(.+) ([A-Z0-9]{6}) ([A-Z0-9]{2}[0-9]{1,4}) (\\d{4}-\\d{2}-\\d{2}) (\\S+) (\\d{1,6})$");

    /** What a payload says, in order of the fields. {@code payload} is the text that was signed. */
    public record Parsed(String name, String locator, String flightNumber, LocalDate departureDate, String seat, int sequence, String payload) {
    }

    private BoardingPassPayload() {
    }

    public static String of(String name, String locator, String flightNumber, LocalDate departureDate, String seat, int sequence) {
        return "M1%s %s %s %s %s %04d".formatted(name, locator, flightNumber, departureDate, seat, sequence);
    }

    /** @param signed the payload followed by {@code .} and its signature; the signature is not checked here */
    public static Optional<Parsed> parse(String signed) {
        int dot = signed == null ? -1 : signed.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String payload = signed.substring(0, dot);
        Matcher found = READ.matcher(payload);
        if (!found.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parsed(found.group(1), found.group(2), found.group(3), LocalDate.parse(found.group(4)), found.group(5),
                    Integer.parseInt(found.group(6)), payload));
        } catch (DateTimeParseException | NumberFormatException notAPass) {
            return Optional.empty();
        }
    }
}
