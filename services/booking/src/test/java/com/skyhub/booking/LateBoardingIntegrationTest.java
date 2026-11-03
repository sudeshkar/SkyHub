package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Boarding a passenger after the gate has closed (ADR-0076), on real PostgreSQL through the real filter chain. The test flights leave in
 * 5 hours (see TestFlightCatalog); boarding here closes 6 hours before departure, so the gate is already closed and the flight has not left.
 */
@SpringBootTest(properties = {"skyhub.booking.boarding.opens-before=PT100H", "skyhub.booking.boarding.closes-before=PT6H"})
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class LateBoardingIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private record Booked(String locator, String token, String barcode) {
    }

    private Booked checkedIn(String date, String seat) throws Exception {
        String locator = JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                         "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                         "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(date, seat)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.locator");
        String token = "Bearer " + JsonPath.<String>read(mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"Perera\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String barcode = JsonPath.read(mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":0}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");
        return new Booked(locator, token, barcode);
    }

    private ResultActions board(String path, String barcode, String reason, String authorization) throws Exception {
        String body = reason == null ? "{\"barcode\":\"%s\"}".formatted(barcode) : "{\"barcode\":\"%s\",\"reason\":\"%s\"}".formatted(barcode, reason);
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private static final String ORDINARY = "/api/v1/ops/boarding-passes/boardings";
    private static final String LATE = "/api/v1/ops/boarding-passes/late-boardings";

    @Test
    void should_let_operations_board_a_passenger_after_the_gate_closed_keep_why_and_show_it_in_the_flights_list() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        Booked trip = checkedIn("2031-01-01", "21A");

        board(ORDINARY, trip.barcode(), null, ops).andExpect(jsonPath("$.reason").value("BOARDING_CLOSED"));   // the ordinary gate cannot
        board(LATE, trip.barcode(), "Held at security", ops).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.pass.passengerName").value("PERERA/PRIYA"));

        assertThat(jdbc.queryForMap("SELECT boarded_by, late_reason FROM boarded_passenger WHERE locator = ?", trip.locator()))
                .containsEntry("late_reason", "Held at security");
        mvc.perform(get("/api/v1/ops/flights/SK501/departures/2031-01-01/boardings").header("Authorization", ops)).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardings[0].locator").value(trip.locator())).andExpect(jsonPath("$.boardings[0].late").value(true));
        board(LATE, trip.barcode(), "Again", ops).andExpect(jsonPath("$.reason").value("ALREADY_BOARDED"));   // the first one counts
    }

    @Test
    void should_never_let_a_gate_agent_a_traveller_or_nobody_board_late_and_refuse_a_missing_reason() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        Booked trip = checkedIn("2031-01-02", "21B");
        mvc.perform(post("/api/v1/ops/staff").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"gate.late.one\",\"password\":\"a long gate passphrase\",\"role\":\"GATE\"}")).andExpect(status().isCreated());
        String gate = TestStaff.login(mvc, "gate.late.one", "a long gate passphrase");

        board(LATE, trip.barcode(), "Held", gate).andExpect(status().isForbidden());
        board(LATE, trip.barcode(), "Held", trip.token()).andExpect(status().isForbidden());
        board(LATE, trip.barcode(), "Held", null).andExpect(status().isUnauthorized());
        board(LATE, trip.barcode(), null, ops).andExpect(status().isBadRequest());
        board(LATE, trip.barcode(), "   ", ops).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarded_passenger WHERE locator = ?", Integer.class, trip.locator())).isZero();
    }

    @Test
    void should_keep_the_late_reason_in_the_record_when_the_boarding_is_taken_back_and_allow_boarding_again() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        Booked trip = checkedIn("2031-01-03", "21C");
        board(LATE, trip.barcode(), "Held at security", ops).andExpect(jsonPath("$.valid").value(true));

        mvc.perform(post("/api/v1/ops/boardings/reversals").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                .content("{\"locator\":\"%s\",\"segmentIndex\":0,\"passengerIndex\":0,\"reason\":\"Wrong passenger\"}".formatted(trip.locator())))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT late_reason FROM boarding_reversal WHERE locator = ?", String.class, trip.locator()))
                .isEqualTo("Held at security");
        board(LATE, trip.barcode(), "Held at security, again", ops).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void should_show_an_ordinary_boarding_as_not_late() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        Booked trip = checkedIn("2031-01-04", "21D");
        jdbc.update("INSERT INTO boarded_passenger (locator, segment_index, passenger_index, boarded_by, boarded_at) VALUES (?, 0, 0, 'gate.one', now())", trip.locator());

        mvc.perform(get("/api/v1/ops/flights/SK501/departures/2031-01-04/boardings").header("Authorization", ops)).andExpect(status().isOk())
                .andExpect(jsonPath("$.boardings[0].late").value(false));
    }
}
