package com.skyhub.booking.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.skyhub.booking.application.view.SeatView;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Short-lived seat maps: a popular flight is read from the database at most once every few seconds
 * per instance, however many people are looking at it. Bookings on this instance clear the entry at
 * once; other instances may show a seat as free for up to {@link #TTL}. That is safe: the booking itself
 * is protected by optimistic locking, and a taken seat returns 409 with a fresh map.
 */
@Component
public class SeatMapCache {

    static final Duration TTL = Duration.ofSeconds(5);

    private final Cache<String, List<SeatView>> maps = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(TTL)
            .build();

    public List<SeatView> get(String flightNumber, LocalDate departureDate, Supplier<List<SeatView>> loader) {
        return maps.get(key(flightNumber, departureDate), k -> List.copyOf(loader.get()));
    }

    public void invalidate(String flightNumber, LocalDate departureDate) {
        maps.invalidate(key(flightNumber, departureDate));
    }

    private static String key(String flightNumber, LocalDate departureDate) {
        return flightNumber + "@" + departureDate;
    }
}
