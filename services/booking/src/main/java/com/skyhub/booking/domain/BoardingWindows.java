package com.skyhub.booking.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which boarding window applies to a flight (ADR-0077): the standard one, or the one set for its route. A route is an origin and a
 * destination as two airport codes in that order ({@code DXB-JFK}); the flight back is a different route. A flight whose route is not
 * known gets the standard window. Pure and immutable: it is built once from the settings and never looks anything up.
 */
public final class BoardingWindows {

    private static final Pattern ROUTE = Pattern.compile("^[A-Za-z]{3}-[A-Za-z]{3}$");

    private final BoardingWindow standard;
    private final Map<String, BoardingWindow> byRoute;

    /**
     * @param byRoute windows for particular routes, keyed {@code ORIGIN-DESTINATION} in any case; may be null or empty
     * @throws IllegalArgumentException if a key is not two airport codes, naming it
     */
    public BoardingWindows(BoardingWindow standard, Map<String, BoardingWindow> byRoute) {
        this.standard = standard;
        Map<String, BoardingWindow> normalised = new HashMap<>();
        if (byRoute != null) {
            byRoute.forEach((key, window) -> {
                if (key == null || !ROUTE.matcher(key).matches()) {
                    throw new IllegalArgumentException("A route must be two three-letter airport codes such as DXB-JFK, not '" + key + "'");
                }
                normalised.put(key.toUpperCase(Locale.ROOT), window);
            });
        }
        this.byRoute = Map.copyOf(normalised);
    }

    public BoardingWindow standard() {
        return standard;
    }

    /** The window for this route, or the standard one when none is set for it or either end is not known. */
    public BoardingWindow forRoute(String origin, String destination) {
        if (origin == null || destination == null) {
            return standard;
        }
        return byRoute.getOrDefault((origin + "-" + destination).toUpperCase(Locale.ROOT), standard);
    }
}
