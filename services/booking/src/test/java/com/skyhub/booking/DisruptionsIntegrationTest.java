package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Delays and cancellations on real PostgreSQL through the real filter chain (ADR-0014). SK888 is a flight whose
 * check-in is already closed and SK501 one whose check-in is open (see TestFlightCatalog), so a delay can be seen
 * to move the window either way. Every test uses its own flight date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class DisruptionsIntegrationTest {

    private String ops; // an Authorization header value for the bootstrap administrator

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void should_record_a_delay_with_the_ops_key_and_count_only_active_bookings() throws Exception {
        book("SK501", "2027-03-01", "11D");
        String cancelled = book("SK501", "2027-03-01", "11E");
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", cancelled).header("Authorization", tokenFor(cancelled)))
                .andExpect(status().isOk());

        mvc.perform(record("SK501", "2027-03-01", delayTo(Duration.ofHours(6)), ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedBookings").value(1));
    }

    @Test
    void should_keep_the_endpoint_closed_without_the_key() throws Exception {
        mvc.perform(record("SK501", "2027-03-02", delayTo(Duration.ofHours(6)), null)).andExpect(status().isUnauthorized());
        mvc.perform(record("SK501", "2027-03-02", delayTo(Duration.ofHours(6)), "Bearer not-a-token")).andExpect(status().isUnauthorized());
        assertThat(rows("2027-03-02")).isZero();
    }

    @Test
    void should_hold_one_record_per_flight_and_date_however_often_it_is_recorded() throws Exception {
        mvc.perform(record("SK501", "2027-03-03", delayTo(Duration.ofHours(6)), ops)).andExpect(status().isOk());
        mvc.perform(record("SK501", "2027-03-03", delayTo(Duration.ofHours(7)), ops)).andExpect(status().isOk());
        mvc.perform(record("SK501", "2027-03-03", "{\"type\":\"CANCELLED\",\"reason\":\"Aircraft grounded\"}", ops))
                .andExpect(status().isOk());

        assertThat(rows("2027-03-03")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT type FROM disruption WHERE departure_date = '2027-03-03'", String.class))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT revised_departure IS NULL FROM disruption WHERE departure_date = '2027-03-03'",
                Boolean.class)).isTrue();
    }

    @Test
    void should_refuse_inconsistent_requests() throws Exception {
        mvc.perform(record("SK501", "2027-03-04", "{\"type\":\"DELAYED\"}", ops)).andExpect(status().isBadRequest());
        mvc.perform(record("SK501", "2027-03-04", delayTo(Duration.ofHours(6)).replace("DELAYED", "CANCELLED"), ops))
                .andExpect(status().isBadRequest());
        assertThat(rows("2027-03-04")).isZero();
    }

    @Test
    void should_show_a_traveller_the_disruptions_on_their_booking_only() throws Exception {
        String mine = book("SK501", "2027-03-05", "11D");
        String other = book("SK901", "2027-03-05", "11D");
        String token = tokenFor(mine);
        mvc.perform(get("/api/v1/pnrs/{l}/disruptions", mine).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());

        mvc.perform(record("SK501", "2027-03-05", delayTo(Duration.ofHours(6)), ops)).andExpect(status().isOk());
        mvc.perform(record("SK901", "2027-03-05", "{\"type\":\"CANCELLED\",\"reason\":\"Weather\"}", ops)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/pnrs/{l}/disruptions", mine).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].flightNumber").value("SK501"))
                .andExpect(jsonPath("$.items[0].type").value("DELAYED"))
                .andExpect(jsonPath("$.items[0].segmentIndex").value(0));
        mvc.perform(get("/api/v1/pnrs/{l}/disruptions", other).header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void should_not_let_a_cancelled_flight_be_checked_in() throws Exception {
        String locator = book("SK501", "2027-03-06", "11D");
        mvc.perform(record("SK501", "2027-03-06", "{\"type\":\"CANCELLED\",\"reason\":\"Aircraft grounded\"}", ops))
                .andExpect(status().isOk());

        mvc.perform(checkIn(locator))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Flight cancelled"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in WHERE locator = ?", Integer.class, locator)).isZero();
    }

    @Test
    void should_close_check_in_until_the_delayed_flights_window_opens() throws Exception {
        String locator = book("SK501", "2027-03-07", "11D"); // scheduled in 5 hours: check-in would be open
        mvc.perform(record("SK501", "2027-03-07", delayTo(Duration.ofHours(40)), ops)).andExpect(status().isOk());

        mvc.perform(checkIn(locator))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("opens")));
    }

    @Test
    void should_open_check_in_for_a_flight_whose_delay_moved_it_back_into_the_window() throws Exception {
        String locator = book("SK888", "2027-03-08", "11D"); // scheduled in 30 minutes: check-in closed
        mvc.perform(checkIn(locator)).andExpect(status().isConflict());

        mvc.perform(record("SK888", "2027-03-08", delayTo(Duration.ofHours(5)), ops)).andExpect(status().isOk());

        mvc.perform(checkIn(locator)).andExpect(status().isCreated());
    }

    @org.junit.jupiter.api.BeforeEach
    void logIn() throws Exception {
        ops = TestStaff.adminBearer(mvc);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String delayTo(Duration fromNow) {
        return "{\"type\":\"DELAYED\",\"revisedDeparture\":\"%s\",\"reason\":\"Late inbound aircraft\"}"
                .formatted(Instant.now().plus(fromNow));
    }

    /** The body gets the flight and date added; {@code partial} holds the rest as a JSON object. */
    private static MockHttpServletRequestBuilder record(String flight, String date, String partial, String key) {
        String body = "{\"flightNumber\":\"%s\",\"departureDate\":\"%s\",%s".formatted(flight, date, partial.substring(1));
        MockHttpServletRequestBuilder request = post("/api/v1/ops/disruptions")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("Authorization", key);
    }

    private long rows(String date) {
        return jdbc.queryForObject("SELECT count(*) FROM disruption WHERE departure_date = ?::date", Long.class, date);
    }

    private final java.util.Map<String, String> tokens = new java.util.HashMap<>();

    private MockHttpServletRequestBuilder checkIn(String locator) throws Exception {
        return post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", tokenFor(locator))
                .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":0}");
    }

    private String book(String flight, String date, String seat) throws Exception {
        String body = """
                {
                  "paymentToken": "tok_visa",
                  "contactEmail": "priya@example.com",
                  "segments": [{"flightNumber": "%s", "departureDate": "%s"}],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%s"]}]
                }
                """.formatted(flight, date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private String tokenFor(String locator) throws Exception {
        String cached = tokens.get(locator);
        if (cached != null) {
            return cached;
        }
        String response = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + JsonPath.<String>read(response, "$.accessToken");
        tokens.put(locator, token);
        return token;
    }
}
