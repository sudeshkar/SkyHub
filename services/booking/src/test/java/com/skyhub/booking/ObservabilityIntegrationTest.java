package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** Metrics, logs and the management port, checked on a real running service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIntegrationTest {

    private static final String EMAIL = "observe.me@example.com";
    private static final String FIRST_NAME = "Zelda";
    private static final String LAST_NAME = "Quixote";

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Test
    void should_record_metrics_and_log_events_without_personal_data(CapturedOutput output) {
        String locator = book("2026-12-01", "21A");

        String metrics = RestClient.create().get()
                .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .retrieve().body(String.class);

        assertThat(metrics)
                .contains("skyhub_bookings_total{application=\"booking\",outcome=\"created\"}")
                .contains("http_server_requests_seconds_bucket")
                .contains("cache_gets_total{application=\"booking\",cache=\"seatInventory\"")
                .contains("hikaricp_connections_active");

        assertThat(output.getOut())
                .contains("\"message\":\"Booking created\"")
                .contains("\"pnr\":{\"locator\":\"" + locator + "\"")
                .contains("\"traceId\"")
                .doesNotContain(EMAIL)
                .doesNotContain(FIRST_NAME)
                .doesNotContain(LAST_NAME);
    }

    @Test
    void should_not_serve_actuator_on_the_public_port() {
        var status = RestClient.create().get()
                .uri("http://localhost:" + port + "/actuator/prometheus")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isIn(403, 404);
    }

    private String book(String date, String seat) {
        String body = """
                {
                  "paymentToken": "tok_visa",
                  "contactEmail": "%s",
                  "segments": [{"flightNumber": "SK501", "departureDate": "%s"}],
                  "passengers": [{"firstName": "%s", "lastName": "%s", "seats": ["%s"]}]
                }
                """.formatted(EMAIL, date, FIRST_NAME, LAST_NAME, seat);
        String response = RestClient.create().post()
                .uri("http://localhost:" + port + "/api/v1/pnrs")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class);
        return com.jayway.jsonpath.JsonPath.read(response, "$.locator");
    }
}
