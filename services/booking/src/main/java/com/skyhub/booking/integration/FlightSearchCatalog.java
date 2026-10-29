package com.skyhub.booking.integration;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.skyhub.booking.application.FlightCatalog;
import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.application.FlightCatalogUnavailableException;
import com.skyhub.booking.domain.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Adapter: asks the flight-search service whether a flight operates and when it leaves, and caches the answer.
 * "Yes" is cached for an hour (timetables change rarely); "no" for a minute, so a flood of made-up
 * flight numbers cannot turn into a flood of calls. Concurrent lookups of the same flight share one
 * call (Caffeine computes each key once). Failures are never cached.
 */
@Component
public class FlightSearchCatalog implements FlightCatalog {

    private static final Duration KNOWN_FLIGHT_TTL = Duration.ofHours(1);
    private static final Duration UNKNOWN_FLIGHT_TTL = Duration.ofMinutes(1);

    private final RestClient client;
    private final Cache<String, Optional<FlightInfo>> answers = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfter(Expiry.creating((String key, Optional<FlightInfo> info) ->
                    info.isPresent() ? KNOWN_FLIGHT_TTL : UNKNOWN_FLIGHT_TTL))
            .recordStats()
            .build();
    private final Counter operatesCalls;
    private final Counter unknownCalls;
    private final Counter unavailableCalls;

    /** Uses Spring's builder, which is instrumented: calls are timed and carry the trace context. */
    public FlightSearchCatalog(RestClient.Builder builder,
                               @Value("${skyhub.flight-search.url:http://localhost:8081}") String baseUrl,
                               MeterRegistry registry) {
        // Timeouts come from spring.http.clients.* in application.yml.
        this.client = builder.clone().baseUrl(baseUrl).build();
        this.operatesCalls = calls(registry, "operates");
        this.unknownCalls = calls(registry, "unknown");
        this.unavailableCalls = calls(registry, "unavailable");
        CaffeineCacheMetrics.monitor(registry, answers, "flightCatalog");
    }

    private static Counter calls(MeterRegistry registry, String result) {
        return Counter.builder("skyhub.flight.catalog.calls")
                .description("Calls from booking to flight-search, by result")
                .tag("result", result)
                .register(registry);
    }

    @Override
    public Optional<FlightInfo> find(String flightNumber, LocalDate departureDate) {
        return answers.get(flightNumber + "@" + departureDate, key -> ask(flightNumber, departureDate));
    }

    /** The part of flight-search's answer we use. */
    private record Leg(OffsetDateTime departureTime, BigDecimal fare, String currency) {
    }

    private Optional<FlightInfo> ask(String flightNumber, LocalDate departureDate) {
        try {
            Optional<FlightInfo> info = client.get()
                    .uri("/api/v1/flights/{flightNumber}/departures/{date}", flightNumber, departureDate)
                    .exchange((request, response) -> switch (response.getStatusCode().value()) {
                        case 200 -> {
                            Leg leg = response.bodyTo(Leg.class);
                            if (leg == null || leg.departureTime() == null || leg.fare() == null || leg.currency() == null) {
                                throw new FlightCatalogUnavailableException("Flight search sent an incomplete flight", null);
                            }
                            yield Optional.of(new FlightInfo(leg.departureTime().toInstant(),
                                    Money.of(leg.fare(), leg.currency())));
                        }
                        case 404 -> Optional.<FlightInfo>empty();
                        default -> throw new FlightCatalogUnavailableException(
                                "Flight search answered " + response.getStatusCode().value(), null);
                    });
            (info.isPresent() ? operatesCalls : unknownCalls).increment();
            return info;
        } catch (RestClientException e) {
            unavailableCalls.increment();
            throw new FlightCatalogUnavailableException("Flight search is unreachable", e);
        } catch (FlightCatalogUnavailableException e) {
            unavailableCalls.increment();
            throw e;
        }
    }
}
