package com.skyhub.booking.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.skyhub.booking.application.FlightCatalog;
import com.skyhub.booking.application.FlightCatalogUnavailableException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter: asks the flight-search service whether a flight operates, and caches the answer.
 * "Yes" is cached for an hour (timetables change rarely); "no" for a minute, so a flood of made-up
 * flight numbers cannot turn into a flood of calls. Concurrent lookups of the same flight share one
 * call (Caffeine computes each key once). Failures are never cached.
 */
@Component
public class FlightSearchCatalog implements FlightCatalog {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration KNOWN_FLIGHT_TTL = Duration.ofHours(1);
    private static final Duration UNKNOWN_FLIGHT_TTL = Duration.ofMinutes(1);

    private final RestClient client;
    private final Cache<String, Boolean> answers = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfter(Expiry.creating((String key, Boolean operates) -> operates ? KNOWN_FLIGHT_TTL : UNKNOWN_FLIGHT_TTL))
            .build();

    @Autowired
    public FlightSearchCatalog(@Value("${skyhub.flight-search.url:http://localhost:8081}") String baseUrl) {
        this(RestClient.builder().requestFactory(timeouts()), baseUrl);
    }

    FlightSearchCatalog(RestClient.Builder builder, String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    private static JdkClientHttpRequestFactory timeouts() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Override
    public boolean operates(String flightNumber, LocalDate departureDate) {
        return answers.get(flightNumber + "@" + departureDate, key -> ask(flightNumber, departureDate));
    }

    private boolean ask(String flightNumber, LocalDate departureDate) {
        try {
            return client.get()
                    .uri("/api/v1/flights/{flightNumber}/departures/{date}", flightNumber, departureDate)
                    .exchange((request, response) -> switch (response.getStatusCode().value()) {
                        case 200 -> true;
                        case 404 -> false;
                        default -> throw new FlightCatalogUnavailableException(
                                "Flight search answered " + response.getStatusCode().value(), null);
                    });
        } catch (RestClientException e) {
            throw new FlightCatalogUnavailableException("Flight search is unreachable", e);
        }
    }
}
