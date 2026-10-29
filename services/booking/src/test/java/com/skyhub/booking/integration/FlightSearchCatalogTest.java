package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.skyhub.booking.application.FlightCatalogUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FlightSearchCatalogTest {

    private static final String BASE = "http://flight-search";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FlightSearchCatalog catalog = new FlightSearchCatalog(builder, BASE, new SimpleMeterRegistry());

    private static final String LEG = "{\"flightNumber\":\"SK501\",\"departureTime\":\"2026-11-02T10:30:00+04:00\",\"fare\":310.5,\"currency\":\"USD\"}";

    @Test
    void should_confirm_an_operating_flight_and_cache_the_answer() {
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK501/departures/2026-11-02"))
                .andRespond(withSuccess(LEG, MediaType.APPLICATION_JSON));

        assertThat(catalog.operates("SK501", DATE)).isTrue();
        assertThat(catalog.operates("SK501", DATE)).isTrue();
        server.verify();
    }

    @Test
    void should_report_when_the_flight_leaves_as_an_instant() {
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK501/departures/2026-11-02"))
                .andRespond(withSuccess(LEG, MediaType.APPLICATION_JSON));

        assertThat(catalog.departure("SK501", DATE)).contains(Instant.parse("2026-11-02T06:30:00Z"));
    }

    @Test
    void should_report_the_fare_as_money() {
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK501/departures/2026-11-02"))
                .andRespond(withSuccess(LEG, MediaType.APPLICATION_JSON));

        assertThat(catalog.find("SK501", DATE).orElseThrow().fare()).isEqualTo(com.skyhub.booking.domain.Money.of("310.50", "USD"));
    }

    @Test
    void should_treat_an_answer_without_a_departure_time_as_unavailable() {
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK501/departures/2026-11-02"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(FlightCatalogUnavailableException.class).isThrownBy(() -> catalog.departure("SK501", DATE));
    }

    @Test
    void should_cache_unknown_flights_too_so_floods_do_not_reach_flight_search() {
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK999/departures/2026-11-02"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(catalog.operates("SK999", DATE)).isFalse();
        assertThat(catalog.operates("SK999", DATE)).isFalse();
        server.verify();
    }

    @Test
    void should_report_unavailable_and_not_cache_failures() {
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK501/departures/2026-11-02")).andRespond(withServerError());
        server.expect(once(), requestTo(BASE + "/api/v1/flights/SK501/departures/2026-11-02"))
                .andRespond(withSuccess(LEG, MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(FlightCatalogUnavailableException.class).isThrownBy(() -> catalog.operates("SK501", DATE));
        assertThat(catalog.operates("SK501", DATE)).isTrue();
        server.verify();
    }
}
