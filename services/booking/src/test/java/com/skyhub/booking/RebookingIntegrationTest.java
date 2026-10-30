package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
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
 * Staff moving a booking off a disrupted flight (ADR-0023), on real PostgreSQL through the real filter chain. In
 * TestFlightCatalog every flight flies DXB to LHR at the same time, except SK610 which flies DXB to JFK.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class RebookingIntegrationTest {

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
    void should_move_a_party_to_seats_side_by_side_and_free_the_old_ones() throws Exception {
        String locator = book("SK501", "2027-09-01", "30A", "30B");
        disrupt("SK501", "2027-09-01", "CANCELLED");

        mvc.perform(rebook(locator, 0, "SK502", "2027-09-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.from.flightNumber").value("SK501"))
                .andExpect(jsonPath("$.to.flightNumber").value("SK502"))
                .andExpect(jsonPath("$.seats.length()").value(2));

        List<String> seats = jdbc.queryForList("SELECT seat_number FROM seat_inventory WHERE pnr_locator = ? AND flight_number = 'SK502' ORDER BY seat_number", String.class, locator);
        assertThat(seats).hasSize(2);
        assertThat(seats.get(0).substring(0, seats.get(0).length() - 1)).isEqualTo(seats.get(1).substring(0, seats.get(1).length() - 1)); // same row
        assertThat(seatStatus("SK501", "2027-09-01", "30A")).isEqualTo("AVAILABLE");
        assertThat(seatStatus("SK501", "2027-09-01", "30B")).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_inventory WHERE pnr_locator = ?", Long.class, locator)).isEqualTo(2);
    }

    @Test
    void should_show_the_traveller_the_new_flight_and_seats() throws Exception {
        String locator = book("SK501", "2027-09-02", "30A");
        disrupt("SK501", "2027-09-02", "DELAYED");

        mvc.perform(rebook(locator, 0, "SK502", "2027-09-03")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", tokenFor(locator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].flightNumber").value("SK502"))
                .andExpect(jsonPath("$.segments[0].departureDate").value("2027-09-03"));
    }

    @Test
    void should_record_who_did_it_and_queue_one_email_event_in_the_same_transaction() throws Exception {
        String locator = book("SK501", "2027-09-04", "30A");
        disrupt("SK501", "2027-09-04", "CANCELLED");

        mvc.perform(rebook(locator, 0, "SK502", "2027-09-04")).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("SELECT rebooked_by, from_flight, to_flight, to_date FROM rebooking WHERE locator = ?", locator);
        assertThat(row.get("rebooked_by")).isEqualTo(TestStaff.ADMIN_USERNAME);
        assertThat(row.get("from_flight")).isEqualTo("SK501");
        assertThat(row.get("to_flight")).isEqualTo("SK502");
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY seq", String.class, locator))
                .contains("booking.confirmed", "booking.rebooked");
    }

    @Test
    void should_change_nothing_and_say_so_when_asked_again() throws Exception {
        String locator = book("SK501", "2027-09-05", "30A");
        disrupt("SK501", "2027-09-05", "CANCELLED");
        mvc.perform(rebook(locator, 0, "SK502", "2027-09-05")).andExpect(status().isOk());

        mvc.perform(rebook(locator, 0, "SK502", "2027-09-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(false));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, locator)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'booking.rebooked'",
                Long.class, locator)).isEqualTo(1);
    }

    @Test
    void should_still_refund_in_full_a_traveller_moved_off_a_flight_the_airline_cancelled() throws Exception {
        // SK888 was 30 minutes away and is cancelled. The replacement, SK501, is 5 hours away: cancelling that would
        // normally refund nothing (less than 24 hours' notice), but the airline cancelled the flight that was booked.
        String locator = book("SK888", "2027-09-10", "30A");
        disrupt("SK888", "2027-09-10", "CANCELLED");
        mvc.perform(rebook(locator, 0, "SK501", "2027-09-10")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("REFUNDED"));
    }

    @Test
    void should_not_give_that_refund_to_a_traveller_moved_off_a_flight_that_was_only_delayed() throws Exception {
        String locator = book("SK888", "2027-09-11", "30A");
        disrupt("SK888", "2027-09-11", "DELAYED");
        mvc.perform(rebook(locator, 0, "SK501", "2027-09-11")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("NOT_REFUNDABLE"));
    }

    @Test
    void should_refuse_to_move_a_booking_off_a_flight_that_is_not_disrupted() throws Exception {
        String locator = book("SK501", "2027-09-06", "30A");

        mvc.perform(rebook(locator, 0, "SK502", "2027-09-06"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no recorded delay or cancellation")));

        assertThat(seatStatus("SK501", "2027-09-06", "30A")).isEqualTo("BOOKED");
    }

    @Test
    void should_refuse_a_flight_on_another_route_and_leave_everything_as_it_was() throws Exception {
        String locator = book("SK501", "2027-09-07", "30A");
        disrupt("SK501", "2027-09-07", "CANCELLED");

        mvc.perform(rebook(locator, 0, TestFlightCatalog.OTHER_ROUTE_FLIGHT, "2027-09-07"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("DXB to JFK")));

        assertThat(seatStatus("SK501", "2027-09-07", "30A")).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rebooking WHERE locator = ?", Long.class, locator)).isZero();
    }

    @Test
    void should_refuse_a_flight_that_does_not_operate_and_a_cancelled_target_and_a_cancelled_booking() throws Exception {
        String locator = book("SK501", "2027-09-08", "30A");
        disrupt("SK501", "2027-09-08", "CANCELLED");
        disrupt("SK502", "2027-09-08", "CANCELLED");

        mvc.perform(rebook(locator, 0, TestFlightCatalog.UNKNOWN_FLIGHT, "2027-09-08")).andExpect(status().isConflict());
        mvc.perform(rebook(locator, 0, "SK502", "2027-09-08")).andExpect(status().isConflict()); // SK502 is cancelled too

        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", tokenFor(locator))).andExpect(status().isOk());
        mvc.perform(rebook(locator, 0, "SK503", "2027-09-08")).andExpect(status().isConflict());
    }

    @Test
    void should_answer_404_for_an_unknown_booking_and_401_or_403_for_callers_who_are_not_staff() throws Exception {
        mvc.perform(rebook("ZZZ999", 0, "SK502", "2027-09-09")).andExpect(status().isNotFound());

        String locator = book("SK501", "2027-09-09", "30A");
        mvc.perform(post("/api/v1/ops/pnrs/{l}/rebooking", locator).contentType(MediaType.APPLICATION_JSON).content(body(0, "SK502", "2027-09-09")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ops/pnrs/{l}/rebooking", locator).header("Authorization", tokenFor(locator))
                        .contentType(MediaType.APPLICATION_JSON).content(body(0, "SK502", "2027-09-09")))
                .andExpect(status().isForbidden());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String body(int segment, String flight, String date) {
        return "{\"segmentIndex\":%d,\"flightNumber\":\"%s\",\"departureDate\":\"%s\"}".formatted(segment, flight, date);
    }

    private MockHttpServletRequestBuilder rebook(String locator, int segment, String flight, String date) {
        return post("/api/v1/ops/pnrs/{l}/rebooking", locator).header("Authorization", ops)
                .contentType(MediaType.APPLICATION_JSON).content(body(segment, flight, date));
    }

    private void disrupt(String flight, String date, String type) throws Exception {
        String extra = type.equals("DELAYED") ? ",\"revisedDeparture\":\"" + date + "T20:00:00Z\"" : "";
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"%s\",\"departureDate\":\"%s\",\"type\":\"%s\",\"reason\":\"Test\"%s}".formatted(flight, date, type, extra)))
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

    private String tokenFor(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }
}
