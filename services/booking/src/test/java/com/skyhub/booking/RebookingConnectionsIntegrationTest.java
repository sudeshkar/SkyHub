package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * A rebooking keeps the traveller's connections (ADR-0036), on real PostgreSQL through the real filter chain. In TestFlightCatalog
 * every flight takes seven hours and leaves five hours from the start of the run, except SK777 (30 hours) and SK888 (30 minutes).
 * So a replacement for the first flight lands at about hour 12: a trip continuing on SK777 (leaving at hour 30) can be moved, and
 * one continuing on SK901 (leaving at hour 5) cannot. Every test uses its own date.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class RebookingConnectionsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String ops;

    @BeforeEach
    void logIn() throws Exception {
        ops = TestStaff.adminBearer(mvc);
    }

    @Test
    void should_move_the_first_flight_of_a_trip_when_the_replacement_still_connects() throws Exception {
        String locator = book("2028-02-01", "SK501", "SK777");
        disrupt("SK501", "2028-02-01");

        mvc.perform(rebook(locator, 0, "SK502", "2028-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true));
    }

    @Test
    void should_refuse_a_replacement_that_lands_after_the_next_flight_and_change_nothing() throws Exception {
        String locator = book("2028-02-02", "SK501", "SK901");
        disrupt("SK501", "2028-02-02");

        mvc.perform(rebook(locator, 0, "SK502", "2028-02-02"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("SK502"), org.hamcrest.Matchers.containsString("SK901"))));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, locator)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM seat_inventory WHERE flight_number = 'SK501' AND departure_date = '2028-02-02' "
                + "AND seat_number = '30A'", String.class)).isEqualTo("BOOKED");
    }

    @Test
    void should_refuse_a_replacement_that_leaves_before_the_previous_flight_has_landed_enough() throws Exception {
        String locator = book("2028-02-03", "SK501", "SK901");
        disrupt("SK901", "2028-02-03");

        mvc.perform(rebook(locator, 1, "SK903", "2028-02-03"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("SK501")));
    }

    @Test
    void should_apply_the_same_rule_through_the_bulk_endpoint_and_say_which_booking_failed() throws Exception {
        String ok = book("2028-02-04", "SK501", "SK777");
        String missed = book("2028-02-04", "SK501", "SK901", "31A");
        disrupt("SK501", "2028-02-04");

        mvc.perform(post("/api/v1/ops/rebookings").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moves\":[%s,%s]}".formatted(move(missed, "SK502", "2028-02-04"), move(ok, "SK502", "2028-02-04"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].detail").value(org.hamcrest.Matchers.containsString("SK901")))
                .andExpect(jsonPath("$.results[1].outcome").value("MOVED"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static String move(String locator, String flight, String date) {
        return "{\"locator\":\"%s\",\"segmentIndex\":0,\"flightNumber\":\"%s\",\"departureDate\":\"%s\"}".formatted(locator, flight, date);
    }

    private MockHttpServletRequestBuilder rebook(String locator, int segment, String flight, String date) {
        return post("/api/v1/ops/pnrs/{l}/rebooking", locator).header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIndex\":%d,\"flightNumber\":\"%s\",\"departureDate\":\"%s\"}".formatted(segment, flight, date));
    }

    private void disrupt(String flight, String date) throws Exception {
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"%s\",\"departureDate\":\"%s\",\"type\":\"CANCELLED\",\"reason\":\"Test\"}".formatted(flight, date)))
                .andExpect(status().isOk());
    }

    /** One passenger on two flights of the same day; seats 30A/30B on the first and second flight (or {@code firstSeat}). */
    private String book(String date, String first, String second, String... firstSeat) throws Exception {
        String seat = firstSeat.length > 0 ? firstSeat[0] : "30A";
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"%s","departureDate":"%s"},{"flightNumber":"%s","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s","%s"]}]}""".formatted(first, date, second, date, seat, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }
}
