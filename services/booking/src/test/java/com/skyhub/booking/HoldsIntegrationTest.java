package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.application.HoldExpirer;
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
 * Seat holds on real PostgreSQL through the real filter chain (ADR-0012): hold, see it on the seat map,
 * book from it, let it lapse, release it. Every test uses its own flight date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class HoldsIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HoldExpirer expirer;

    @Test
    void should_hold_seats_and_show_them_as_held_on_the_seat_map() throws Exception {
        String holdId = hold("2027-01-05", "11D", "24A");

        assertThat(seatStatus("SK501", "2027-01-05", "11D")).isEqualTo("HELD");
        assertThat(seatStatus("SK901", "2027-01-05", "24A")).isEqualTo("HELD");
        mvc.perform(get("/api/v1/flights/SK501/departures/2027-01-05/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[?(@.seatNumber=='11D')].status").value("HELD"));
        assertThat(holdId).isNotBlank();
    }

    @Test
    void should_refuse_a_second_hold_on_a_held_seat_and_hold_nothing_of_it() throws Exception {
        hold("2027-01-06", "11D", "24A");

        mvc.perform(holdRequest("2027-01-06", "12D", "24A")) // 12D is free, 24A is held
                .andExpect(status().isConflict());

        assertThat(seatStatus("SK501", "2027-01-06", "12D")).isEqualTo("AVAILABLE"); // all or nothing
    }

    @Test
    void should_book_from_a_hold_and_clear_the_hold() throws Exception {
        String holdId = hold("2027-01-07", "11D", "24A");

        mvc.perform(bookRequest("2027-01-07", holdId, "11D", "24A")).andExpect(status().isCreated());

        assertThat(seatStatus("SK501", "2027-01-07", "11D")).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_inventory WHERE hold_id = ?", Integer.class, holdId))
                .isZero();
    }

    @Test
    void should_not_let_anyone_else_book_a_held_seat_directly() throws Exception {
        hold("2027-01-08", "11D", "24A");

        mvc.perform(bookRequest("2027-01-08", null, "11D", "24A")).andExpect(status().isConflict());
        mvc.perform(bookRequest("2027-01-08", UUID.randomUUID().toString(), "11D", "24A")).andExpect(status().isConflict());

        assertThat(seatStatus("SK501", "2027-01-08", "11D")).isEqualTo("HELD");
    }

    @Test
    void should_refuse_an_expired_hold_even_before_the_cleanup_runs() throws Exception {
        String holdId = hold("2027-01-09", "11D", "24A");
        expire(holdId);

        mvc.perform(bookRequest("2027-01-09", holdId, "11D", "24A")).andExpect(status().isConflict());

        assertThat(seatStatus("SK501", "2027-01-09", "11D")).isNotEqualTo("BOOKED");
    }

    @Test
    void should_free_expired_holds_in_one_sweep_and_leave_live_ones_alone() throws Exception {
        String lapsed = hold("2027-01-10", "11D", "24A");
        hold("2027-01-10", "12D", "25A");
        expire(lapsed);

        expirer.expire();

        assertThat(seatStatus("SK501", "2027-01-10", "11D")).isEqualTo("AVAILABLE");
        assertThat(seatStatus("SK901", "2027-01-10", "24A")).isEqualTo("AVAILABLE");
        assertThat(seatStatus("SK501", "2027-01-10", "12D")).isEqualTo("HELD");
        // The freed seats can be held again.
        hold("2027-01-10", "11D", "24A");
    }

    @Test
    void should_release_a_hold_and_be_safe_to_repeat() throws Exception {
        String holdId = hold("2027-01-11", "11D", "24A");

        mvc.perform(delete("/api/v1/holds/{id}", holdId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/holds/{id}", holdId)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/holds/{id}", UUID.randomUUID())).andExpect(status().isNoContent());

        assertThat(seatStatus("SK501", "2027-01-11", "11D")).isEqualTo("AVAILABLE");
    }

    @Test
    void should_not_hold_seats_on_a_flight_that_does_not_operate() throws Exception {
        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content("""
                        {"items": [{"flightNumber": "SK999", "departureDate": "2027-01-12", "seatNumber": "11D"}]}"""))
                .andExpect(status().isNotFound());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private String hold(String date, String firstSeat, String secondSeat) throws Exception {
        String response = mvc.perform(holdRequest(date, firstSeat, secondSeat)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.holdId");
    }

    private static MockHttpServletRequestBuilder holdRequest(String date, String firstSeat, String secondSeat) {
        return post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content("""
                {"items": [
                  {"flightNumber": "SK501", "departureDate": "%1$s", "seatNumber": "%2$s"},
                  {"flightNumber": "SK901", "departureDate": "%1$s", "seatNumber": "%3$s"}
                ]}""".formatted(date, firstSeat, secondSeat));
    }

    private static MockHttpServletRequestBuilder bookRequest(String date, String holdId, String firstSeat, String secondSeat) {
        String hold = holdId == null ? "" : "\"holdId\": \"" + holdId + "\",";
        return post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          %4$s
                          "paymentToken": "tok_visa",
                          "contactEmail": "priya@example.com",
                          "segments": [
                            {"flightNumber": "SK501", "departureDate": "%1$s"},
                            {"flightNumber": "SK901", "departureDate": "%1$s"}
                          ],
                          "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%2$s", "%3$s"]}]
                        }""".formatted(date, firstSeat, secondSeat, hold));
    }

    /** Moves a hold's deadline into the past, as if its ten minutes had gone by. */
    private void expire(String holdId) {
        jdbc.update("UPDATE seat_inventory SET held_until = now() - interval '1 minute' WHERE hold_id = ?", holdId);
    }

    private String seatStatus(String flight, String date, String seat) {
        return jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE flight_number = ? AND departure_date = ?::date AND seat_number = ?",
                String.class, flight, date, seat);
    }
}
