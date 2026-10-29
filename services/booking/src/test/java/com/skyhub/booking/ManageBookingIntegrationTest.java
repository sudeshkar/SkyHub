package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
 * The whole manage-booking journey on real PostgreSQL, through the real security filter chain:
 * book, prove ownership by last name, read, change a seat, cancel, and see the seats come back.
 * Every test uses its own flight date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class ManageBookingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void should_open_a_session_only_with_a_passenger_last_name() throws Exception {
        String locator = book("2026-12-01", "11D", "24A");

        mvc.perform(session(locator, "PERERA")) // case does not matter
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.pnr.locator").value(locator));
        mvc.perform(session(locator, "Silva")).andExpect(status().isNotFound());
        mvc.perform(session("ZZZZZZ", "Perera")).andExpect(status().isNotFound());
    }

    @Test
    void should_give_a_wrong_name_and_an_unknown_reference_the_same_answer() throws Exception {
        String locator = book("2026-12-02", "11D", "24A");

        String wrongName = mvc.perform(session(locator, "Silva")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(session("ZZZZZZ", "Perera")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(wrongName, "$.detail")).isEqualTo(JsonPath.read(unknown, "$.detail"));
    }

    @Test
    void should_read_a_booking_only_with_its_own_token() throws Exception {
        String mine = book("2026-12-03", "11D", "24A");
        String other = book("2026-12-03", "12D", "25A");
        String token = tokenFor(mine);

        mvc.perform(get("/api/v1/pnrs/{locator}", mine).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locator").value(mine));
        mvc.perform(get("/api/v1/pnrs/{locator}", other).header("Authorization", token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pnrs/{locator}", mine)).andExpect(status().isUnauthorized());
    }

    @Test
    void should_move_a_passenger_freeing_the_old_seat_and_taking_the_new_one() throws Exception {
        String locator = book("2026-12-04", "11D", "24A");
        String token = tokenFor(locator);

        mvc.perform(changeSeat(locator, token, 0, 1, "30C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passengers[0].seats[0]").value("11D"))
                .andExpect(jsonPath("$.passengers[0].seats[1]").value("30C"));

        assertThat(seatStatus("SK901", "2026-12-04", "24A")).isEqualTo("AVAILABLE");
        assertThat(seatStatus("SK901", "2026-12-04", "30C")).isEqualTo("BOOKED");
        assertThat(seatStatus("SK501", "2026-12-04", "11D")).isEqualTo("BOOKED"); // other flight untouched
    }

    @Test
    void should_refuse_a_seat_that_is_taken_and_change_nothing() throws Exception {
        String locator = book("2026-12-05", "11D", "24A");
        book("2026-12-05", "14C", "26A"); // someone else holds SK501 14C
        String token = tokenFor(locator);

        mvc.perform(changeSeat(locator, token, 0, 0, "14C"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat unavailable"));

        assertThat(seatStatus("SK501", "2026-12-05", "11D")).isEqualTo("BOOKED");
        mvc.perform(get("/api/v1/pnrs/{locator}", locator).header("Authorization", token))
                .andExpect(jsonPath("$.passengers[0].seats[0]").value("11D"));
    }

    @Test
    void should_cancel_release_every_seat_and_be_safe_to_repeat() throws Exception {
        String locator = book("2026-12-06", "11D", "24A");
        String token = tokenFor(locator);

        mvc.perform(post("/api/v1/pnrs/{locator}/cancellation", locator).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(seatStatus("SK501", "2026-12-06", "11D")).isEqualTo("AVAILABLE");
        assertThat(seatStatus("SK901", "2026-12-06", "24A")).isEqualTo("AVAILABLE");

        // A second cancel is a no-op, and it must not free a seat someone else has since taken.
        book("2026-12-06", "11D", "24A");
        mvc.perform(post("/api/v1/pnrs/{locator}/cancellation", locator).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(seatStatus("SK501", "2026-12-06", "11D")).isEqualTo("BOOKED");
    }

    @Test
    void should_not_allow_seat_changes_after_cancelling() throws Exception {
        String locator = book("2026-12-07", "11D", "24A");
        String token = tokenFor(locator);
        mvc.perform(post("/api/v1/pnrs/{locator}/cancellation", locator).header("Authorization", token))
                .andExpect(status().isOk());

        mvc.perform(changeSeat(locator, token, 0, 0, "30C"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Booking cancelled"));
        assertThat(seatStatus("SK501", "2026-12-07", "30C")).isEqualTo("AVAILABLE");
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private String book(String date, String firstSeat, String secondSeat) throws Exception {
        String body = """
                {
                  "paymentToken": "tok_visa",
                  "contactEmail": "priya@example.com",
                  "segments": [
                    {"flightNumber": "SK501", "departureDate": "%1$s"},
                    {"flightNumber": "SK901", "departureDate": "%1$s"}
                  ],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%2$s", "%3$s"]}]
                }
                """.formatted(date, firstSeat, secondSeat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private static MockHttpServletRequestBuilder session(String locator, String lastName) {
        return post("/api/v1/pnrs/{locator}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"" + lastName + "\"}");
    }

    private String tokenFor(String locator) throws Exception {
        String response = mvc.perform(session(locator, "Perera")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    private static MockHttpServletRequestBuilder changeSeat(String locator, String token, int passenger, int segment,
                                                            String seat) {
        return put("/api/v1/pnrs/{locator}/seat-assignments", locator).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"passengerIndex\":%d,\"segmentIndex\":%d,\"seatNumber\":\"%s\"}".formatted(passenger, segment, seat));
    }

    private String seatStatus(String flight, String date, String seat) {
        return jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE flight_number = ? AND departure_date = ?::date AND seat_number = ?",
                String.class, flight, date, seat);
    }
}
