package com.skyhub.notification.application;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The airports SkyHub flies to, for showing times in local time and names in full (ADR-0087). The same table as the website's
 * {@code frontend/src/lib/airports.ts}; serving it from flight-search, so the copies cannot drift, is the follow-up both name.
 */
public final class Airports {

    /** {@code known} is false for a code not in the table: it is then shown by its code, in UTC. */
    public record Airport(String code, String city, String name, ZoneId zone, boolean known) {

        /** "Colombo, Bandaranaike International", or just the code for an airport not in the table. */
        public String described() {
            return known ? city + ", " + name : code;
        }
    }

    private static final Map<String, Airport> BY_CODE = Stream.of(
                    known("CMB", "Colombo", "Bandaranaike International", "Asia/Colombo"),
                    known("BOM", "Mumbai", "Chhatrapati Shivaji Maharaj International", "Asia/Kolkata"),
                    known("DXB", "Dubai", "Dubai International", "Asia/Dubai"),
                    known("DWC", "Dubai", "Al Maktoum International", "Asia/Dubai"),
                    known("LHR", "London", "Heathrow", "Europe/London"),
                    known("JFK", "New York", "John F. Kennedy International", "America/New_York"),
                    known("SIN", "Singapore", "Changi", "Asia/Singapore"),
                    known("SYD", "Sydney", "Kingsford Smith", "Australia/Sydney"))
            .collect(Collectors.toUnmodifiableMap(Airport::code, Function.identity()));

    private Airports() {
    }

    public static Airport of(String code) {
        Airport airport = BY_CODE.get(code);
        return airport != null ? airport : new Airport(code, code, code, ZoneOffset.UTC, false);
    }

    private static Airport known(String code, String city, String name, String zone) {
        return new Airport(code, city, name, ZoneId.of(zone), true);
    }
}
