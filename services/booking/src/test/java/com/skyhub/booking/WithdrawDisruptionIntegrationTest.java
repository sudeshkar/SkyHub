package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Taking a mistaken disruption back (ADR-0026), on real PostgreSQL through the real filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class WithdrawDisruptionIntegrationTest {

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
    void should_put_a_mistakenly_cancelled_flight_back_on_sale() throws Exception {
        record("SK501", "2027-11-01", "CANCELLED");
        mvc.perform(quote("SK501", "2027-11-01")).andExpect(status().isConflict());

        mvc.perform(withdraw("SK501", "2027-11-01")).andExpect(status().isOk());

        mvc.perform(quote("SK501", "2027-11-01")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM disruption WHERE flight_number = 'SK501' AND departure_date = '2027-11-01'", Long.class)).isZero();
    }

    @Test
    void should_report_how_many_active_bookings_are_on_the_flight() throws Exception {
        record("SK501", "2027-11-02", "DELAYED");
        book("SK501", "2027-11-02", "12A");
        book("SK501", "2027-11-02", "12B");

        mvc.perform(withdraw("SK501", "2027-11-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedBookings").value(2));
    }

    @Test
    void should_say_404_the_second_time_because_there_is_nothing_left_to_withdraw() throws Exception {
        record("SK501", "2027-11-03", "CANCELLED");
        mvc.perform(withdraw("SK501", "2027-11-03")).andExpect(status().isOk());

        mvc.perform(withdraw("SK501", "2027-11-03")).andExpect(status().isNotFound());
    }

    @Test
    void should_queue_the_cleared_state_for_search_in_the_same_transaction() throws Exception {
        record("SK501", "2027-11-04", "CANCELLED");

        mvc.perform(withdraw("SK501", "2027-11-04")).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = 'SK501@2027-11-04' ORDER BY seq", String.class))
                .containsExactly("flight.disrupted", "flight.disrupted");   // the cancellation, then the clearing that replaces it
    }

    @Test
    void should_refuse_once_a_booking_was_moved_off_the_flight_and_change_nothing() throws Exception {
        String locator = book("SK501", "2027-11-05", "12A");
        record("SK501", "2027-11-05", "CANCELLED");
        mvc.perform(post("/api/v1/ops/pnrs/{l}/rebooking", locator).header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentIndex\":0,\"flightNumber\":\"SK502\",\"departureDate\":\"2027-11-05\"}"))
                .andExpect(status().isOk());

        mvc.perform(withdraw("SK501", "2027-11-05"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("moved")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM disruption WHERE flight_number = 'SK501' AND departure_date = '2027-11-05'", Long.class)).isEqualTo(1);
    }

    @Test
    void should_keep_it_from_callers_who_are_not_staff() throws Exception {
        record("SK501", "2027-11-06", "CANCELLED");

        mvc.perform(delete("/api/v1/ops/disruptions/SK501/2027-11-06")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM disruption WHERE flight_number = 'SK501' AND departure_date = '2027-11-06'", Long.class)).isEqualTo(1);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder withdraw(String flight, String date) {
        return delete("/api/v1/ops/disruptions/{f}/{d}", flight, date).header("Authorization", ops);
    }

    private void record(String flight, String date, String type) throws Exception {
        String extra = type.equals("DELAYED") ? ",\"revisedDeparture\":\"" + date + "T20:00:00Z\"" : "";
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"%s\",\"departureDate\":\"%s\",\"type\":\"%s\",\"reason\":\"Test\"%s}".formatted(flight, date, type, extra)))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder quote(String flight, String date) {
        return post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"segments\":[{\"flightNumber\":\"%s\",\"departureDate\":\"%s\"}],\"passengerCount\":1}".formatted(flight, date));
    }

    private String book(String flight, String date, String seat) throws Exception {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"%s","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(flight, date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }
}
