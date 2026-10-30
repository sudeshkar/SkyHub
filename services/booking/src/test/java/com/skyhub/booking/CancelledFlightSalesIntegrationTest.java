package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * A cancelled flight is not for sale (ADR-0025): no price, no hold, no booking and above all no charge. Real PostgreSQL,
 * through the real filter chain. A delayed flight is still sold: the traveller can choose it knowing the new time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class CancelledFlightSalesIntegrationTest {

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
    void should_refuse_a_quote_a_hold_and_a_booking_for_a_cancelled_flight_and_charge_nothing() throws Exception {
        disrupt("SK501", "2027-10-01", "CANCELLED");
        long payments = jdbc.queryForObject("SELECT count(*) FROM payment", Long.class);

        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"segments\":[{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-10-01\"}],\"passengerCount\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Flight cancelled"));
        mvc.perform(post("/api/v1/holds").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"items\":[{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-10-01\",\"seatNumber\":\"12A\"}]}"))
                .andExpect(status().isConflict());
        mvc.perform(book("SK501", "2027-10-01", "12A")).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Long.class)).isEqualTo(payments);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seat_inventory WHERE flight_number = 'SK501' AND departure_date = '2027-10-01' AND status <> 'AVAILABLE'",
                Long.class)).isZero();
    }

    @Test
    void should_refuse_a_trip_in_which_only_one_flight_is_cancelled() throws Exception {
        disrupt("SK502", "2027-10-02", "CANCELLED");

        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"segments\":[{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-10-02\"},"
                                + "{\"flightNumber\":\"SK502\",\"departureDate\":\"2027-10-02\"}],\"passengerCount\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("SK502")));
    }

    @Test
    void should_keep_selling_a_delayed_flight_and_other_dates_of_a_cancelled_one() throws Exception {
        disrupt("SK501", "2027-10-03", "DELAYED");
        disrupt("SK503", "2027-10-03", "CANCELLED");

        mvc.perform(book("SK501", "2027-10-03", "12A")).andExpect(status().isCreated());
        mvc.perform(book("SK503", "2027-10-04", "12A")).andExpect(status().isCreated()); // the next day's flight is fine
    }

    @Test
    void should_still_show_the_seat_map_of_a_cancelled_flight_for_people_holding_a_booking_on_it() throws Exception {
        disrupt("SK501", "2027-10-05", "CANCELLED");

        mvc.perform(get("/api/v1/flights/SK501/departures/2027-10-05/seats")).andExpect(status().isOk());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void disrupt(String flight, String date, String type) throws Exception {
        String extra = type.equals("DELAYED") ? ",\"revisedDeparture\":\"" + date + "T20:00:00Z\"" : "";
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"%s\",\"departureDate\":\"%s\",\"type\":\"%s\",\"reason\":\"Test\"%s}".formatted(flight, date, type, extra)))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder book(String flight, String date, String seat) {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"%s","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(flight, date, seat);
        return post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
