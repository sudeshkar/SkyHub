package com.skyhub.flightsearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/** Metrics and the management port, checked on a real running service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import(PostgresTestcontainer.class)
class ObservabilityIntegrationTest {

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Test
    void should_expose_timetable_and_request_metrics_on_the_management_port() {
        RestClient.create().get()
                .uri("http://localhost:" + port + "/api/v1/itineraries?origin=CMB&destination=JFK&date=2026-11-02")
                .retrieve().toBodilessEntity();

        String metrics = RestClient.create().get()
                .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .retrieve().body(String.class);

        assertThat(metrics)
                .contains("skyhub_timetable_flights{application=\"flight-search\"} 10.0")
                .contains("skyhub_timetable_last_refresh_seconds")
                .contains("http_server_requests_seconds_bucket{application=\"flight-search\"");
    }

    @Test
    void should_report_readiness_on_the_management_port() {
        var status = RestClient.create().get()
                .uri("http://localhost:" + managementPort + "/actuator/health/readiness")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(200);
    }

    @Test
    void should_not_serve_actuator_on_the_public_port() {
        var status = RestClient.create().get()
                .uri("http://localhost:" + port + "/actuator/prometheus")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isIn(403, 404);
    }
}
