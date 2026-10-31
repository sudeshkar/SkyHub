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
 * Moving several bookings in one request (ADR-0035), on real PostgreSQL through the real filter chain. In TestFlightCatalog every
 * flight flies DXB to LHR, except SK610 which flies DXB to JFK. Every test uses its own date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class BulkRebookingIntegrationTest {

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
    void should_move_every_booking_in_one_request_each_with_its_own_audit_row_and_email_event() throws Exception {
        String first = book("SK501", "2028-01-01", "30A", "30B");
        String second = book("SK501", "2028-01-01", "31A");
        disrupt("SK501", "2028-01-01", "CANCELLED");

        String response = mvc.perform(bulk(move(first, "SK502", "2028-01-01"), move(second, "SK502", "2028-01-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].locator").value(first))
                .andExpect(jsonPath("$.results[0].outcome").value("MOVED"))
                .andExpect(jsonPath("$.results[0].seats.length()").value(2))
                .andExpect(jsonPath("$.results[1].locator").value(second))
                .andExpect(jsonPath("$.results[1].outcome").value("MOVED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("Perera").doesNotContain("example.com");
        for (String locator : new String[] {first, second}) {
            assertThat(jdbc.queryForObject("SELECT rebooked_by FROM rebooking WHERE locator = ?", String.class, locator))
                    .isEqualTo(TestStaff.ADMIN_USERNAME);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'booking.rebooked'",
                    Long.class, locator)).isEqualTo(1);
        }
        assertThat(seatStatus("SK501", "2028-01-01", "30A")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_inventory WHERE flight_number = 'SK502' AND departure_date = '2028-01-01' "
                + "AND status = 'BOOKED'", Long.class)).isEqualTo(3);
    }

    @Test
    void should_refuse_one_move_say_why_and_still_move_the_others() throws Exception {
        String good = book("SK501", "2028-01-02", "30A");
        String wrongRoute = book("SK501", "2028-01-02", "31A");
        disrupt("SK501", "2028-01-02", "CANCELLED");

        mvc.perform(bulk(move(wrongRoute, "SK610", "2028-01-02"), move(good, "SK502", "2028-01-02"), move("ZZZ999", "SK502", "2028-01-02")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[0].detail").isNotEmpty())
                .andExpect(jsonPath("$.results[1].outcome").value("MOVED"))
                .andExpect(jsonPath("$.results[2].outcome").value("NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, wrongRoute)).isZero();   // refused: untouched
        assertThat(seatStatus("SK501", "2028-01-02", "31A")).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, good)).isEqualTo(1);
    }

    @Test
    void should_be_safe_to_repeat_after_a_dropped_connection() throws Exception {
        String locator = book("SK501", "2028-01-03", "30A");
        disrupt("SK501", "2028-01-03", "CANCELLED");
        mvc.perform(bulk(move(locator, "SK502", "2028-01-03"))).andExpect(status().isOk());

        mvc.perform(bulk(move(locator, "SK502", "2028-01-03")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.results[0].seats.length()").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, locator)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'booking.rebooked'",
                Long.class, locator)).isEqualTo(1);
    }

    @Test
    void should_change_nothing_for_a_set_that_lists_a_move_twice() throws Exception {
        String locator = book("SK501", "2028-01-04", "30A");
        disrupt("SK501", "2028-01-04", "CANCELLED");

        mvc.perform(bulk(move(locator, "SK502", "2028-01-04"), move(locator, "SK503", "2028-01-04")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(locator)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, locator)).isZero();
        assertThat(seatStatus("SK501", "2028-01-04", "30A")).isEqualTo("BOOKED");
    }

    @Test
    void should_keep_it_from_anyone_who_is_not_staff() throws Exception {
        String locator = book("SK501", "2028-01-05", "30A");
        String manage = manageToken(locator);

        mvc.perform(post("/api/v1/ops/rebookings").contentType(MediaType.APPLICATION_JSON).content(body(move(locator, "SK502", "2028-01-05"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ops/rebookings").header("Authorization", manage).contentType(MediaType.APPLICATION_JSON)
                        .content(body(move(locator, "SK502", "2028-01-05"))))
                .andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private static String move(String locator, String flight, String date) {
        return "{\"locator\":\"%s\",\"segmentIndex\":0,\"flightNumber\":\"%s\",\"departureDate\":\"%s\"}".formatted(locator, flight, date);
    }

    private static String body(String... moves) {
        return "{\"moves\":[" + String.join(",", moves) + "]}";
    }

    private MockHttpServletRequestBuilder bulk(String... moves) {
        return post("/api/v1/ops/rebookings").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON).content(body(moves));
    }

    private void disrupt(String flight, String date, String type) throws Exception {
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"%s\",\"departureDate\":\"%s\",\"type\":\"%s\",\"reason\":\"Test\"}".formatted(flight, date, type)))
                .andExpect(status().isOk());
    }

    private String seatStatus(String flight, String date, String seat) {
        return jdbc.queryForObject("SELECT status FROM seat_inventory WHERE flight_number = ? AND departure_date = ?::date AND seat_number = ?",
                String.class, flight, date, seat);
    }

    private String book(String flight, String date, String... seats) throws Exception {
        StringBuilder passengers = new StringBuilder();
        for (int i = 0; i < seats.length; i++) {
            passengers.append(i > 0 ? "," : "").append("{\"firstName\":\"Priya%d\",\"lastName\":\"Perera\",\"seats\":[\"%s\"]}".formatted(i, seats[i]));
        }
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"%s","departureDate":"%s"}],"passengers":[%s]}""".formatted(flight, date, passengers);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private String manageToken(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }
}
