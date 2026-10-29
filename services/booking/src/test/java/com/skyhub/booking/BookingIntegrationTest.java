package com.skyhub.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Full booking flow against real PostgreSQL. Each test uses its own flight date so tests stay independent. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class BookingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void should_book_a_two_segment_itinerary_and_retrieve_it_by_last_name() throws Exception {
        String response = mvc.perform(book("2026-11-02", "11D", "24A", newKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.passengers[0].seats[1]").value("24A"))
                .andExpect(jsonPath("$.contactEmail").value("p****@example.com"))
                .andReturn().getResponse().getContentAsString();
        String locator = JsonPath.read(response, "$.locator");

        mvc.perform(get("/api/v1/pnrs/{locator}", locator).param("lastName", "PERERA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locator").value(locator));
        mvc.perform(get("/api/v1/pnrs/{locator}", locator).param("lastName", "Silva"))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_show_booked_seats_on_the_seat_map() throws Exception {
        mvc.perform(book("2026-11-03", "12A", "25C", newKey())).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/flights/SK501/departures/2026-11-03/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(168))
                .andExpect(jsonPath("$.seats[0].seatNumber").value("1A"))
                .andExpect(jsonPath("$.seats[?(@.seatNumber == '12A')].status").value("BOOKED"))
                .andExpect(jsonPath("$.seats[?(@.seatNumber == '12B')].status").value("AVAILABLE"));
    }

    @Test
    void should_refuse_a_booked_seat_and_keep_the_other_seat_free() throws Exception {
        mvc.perform(book("2026-11-04", "14C", "26A", newKey())).andExpect(status().isCreated());

        mvc.perform(book("2026-11-04", "14C", "26B", newKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat unavailable"));

        // The failed booking rolled back, so its seat on the second flight is still free.
        mvc.perform(get("/api/v1/flights/SK901/departures/2026-11-04/seats"))
                .andExpect(jsonPath("$.seats[?(@.seatNumber == '26B')].status").value("AVAILABLE"));
    }

    @Test
    void should_replay_the_same_request_without_booking_twice() throws Exception {
        String key = newKey();
        String first = mvc.perform(book("2026-11-05", "15D", "27A", key)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(book("2026-11-05", "15D", "27A", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locator").value(JsonPath.<String>read(first, "$.locator")));
    }

    @Test
    void should_reject_an_idempotency_key_reused_for_another_booking() throws Exception {
        String key = newKey();
        mvc.perform(book("2026-11-06", "16D", "28A", key)).andExpect(status().isCreated());

        mvc.perform(book("2026-11-06", "16E", "28B", key)).andExpect(status().isUnprocessableContent());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static MockHttpServletRequestBuilder book(String date, String firstSeat, String secondSeat, String key) {
        String body = """
                {
                  "contactEmail": "priya@example.com",
                  "segments": [
                    {"flightNumber": "SK501", "departureDate": "%1$s"},
                    {"flightNumber": "SK901", "departureDate": "%1$s"}
                  ],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%2$s", "%3$s"]}]
                }
                """.formatted(date, firstSeat, secondSeat);
        return post("/api/v1/pnrs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
