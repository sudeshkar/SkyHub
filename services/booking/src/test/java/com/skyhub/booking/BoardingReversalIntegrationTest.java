package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Taking back a boarding on real PostgreSQL through the real filter chain (ADR-0064). The test flights leave in 5 hours, so boarding is
 * opened wide as in CheckInIntegrationTest. Each test uses its own flight date so tests stay independent.
 */
@SpringBootTest(properties = "skyhub.booking.boarding.opens-before=PT100H")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class BoardingReversalIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** A booking of two passengers (Priya, Raj), both checked in; returns the locator and each pass. */
    private record Trip(String locator, String token, String priya, String raj) {
    }

    private Trip checkedInTrip(String date) throws Exception {
        String locator = JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                         "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                         "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["22D"]},{"firstName":"Raj","lastName":"Perera","seats":["22E"]}]}"""
                        .formatted(date))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.locator");
        String token = "Bearer " + JsonPath.<String>read(mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"Perera\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");
        String both = mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIndex\":0}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Trip(locator, token, JsonPath.read(both, "$.passes[0].barcode"), JsonPath.read(both, "$.passes[1].barcode"));
    }

    private String staff(String username, String role) throws Exception {
        mvc.perform(post("/api/v1/ops/staff").header("Authorization", TestStaff.adminBearer(mvc)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"a long staff passphrase\",\"role\":\"%s\"}".formatted(username, role)))
                .andExpect(status().isCreated());
        return TestStaff.login(mvc, username, "a long staff passphrase");
    }

    private ResultActions board(String barcode, String authorization) throws Exception {
        return mvc.perform(post("/api/v1/ops/boarding-passes/boardings").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content("{\"barcode\":\"%s\"}".formatted(barcode)));
    }

    private static String undoBody(String locator, int passengerIndex, String reason) {
        return "{\"locator\":\"%s\",\"segmentIndex\":0,\"passengerIndex\":%d,\"reason\":\"%s\"}".formatted(locator, passengerIndex, reason);
    }

    private ResultActions undo(String body, String authorization) throws Exception {
        var request = post("/api/v1/ops/boardings/reversals").contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    private int reversals(String locator) {
        return jdbc.queryForObject("SELECT count(*) FROM boarding_reversal WHERE locator = ?", Integer.class, locator);
    }

    @Test
    void should_let_operations_take_a_boarding_back_keep_the_audit_row_and_let_the_same_pass_board_again() throws Exception {
        String gate = staff("rev.gate1", "GATE");
        String ops = staff("rev.ops1", "OPS");
        Trip trip = checkedInTrip("2029-10-01");
        board(trip.priya(), gate).andExpect(jsonPath("$.valid").value(true));
        board(trip.raj(), gate).andExpect(jsonPath("$.valid").value(true));

        undo(undoBody(trip.locator(), 1, "Scanned the wrong passenger"), ops).andExpect(status().isNoContent());

        // The audit row says who scanned, who corrected, and why; the other passenger is untouched.
        var row = jdbc.queryForMap("SELECT boarded_by, reversed_by, reason, passenger_index FROM boarding_reversal WHERE locator = ?", trip.locator());
        assertThat(row).containsEntry("boarded_by", "rev.gate1").containsEntry("reversed_by", "rev.ops1")
                .containsEntry("reason", "Scanned the wrong passenger").containsEntry("passenger_index", 1);
        assertThat(jdbc.queryForList("SELECT passenger_index FROM boarded_passenger WHERE locator = ?", Integer.class, trip.locator())).containsExactly(0);
        // Raj is still checked in: his pass is good again and boards once more, and Priya is still refused.
        mvc.perform(get("/api/v1/pnrs/{l}/check-ins/0", trip.locator()).header("Authorization", trip.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passes.length()").value(2));
        board(trip.raj(), gate).andExpect(jsonPath("$.valid").value(true));
        board(trip.priya(), gate).andExpect(jsonPath("$.reason").value("ALREADY_BOARDED"));
    }

    @Test
    void should_refuse_a_gate_agent_a_traveller_and_nobody_and_change_nothing() throws Exception {
        String gate = staff("rev.gate2", "GATE");
        Trip trip = checkedInTrip("2029-10-02");
        board(trip.priya(), gate).andExpect(jsonPath("$.valid").value(true));

        undo(undoBody(trip.locator(), 0, "mistake"), gate).andExpect(status().isForbidden());    // the person at the gate cannot erase a scan
        undo(undoBody(trip.locator(), 0, "mistake"), trip.token()).andExpect(status().isForbidden());   // nor can the traveller's booking token
        undo(undoBody(trip.locator(), 0, "mistake"), null).andExpect(status().isUnauthorized());

        assertThat(reversals(trip.locator())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarded_passenger WHERE locator = ?", Integer.class, trip.locator())).isEqualTo(1);
    }

    @Test
    void should_answer_404_the_second_time_and_for_someone_who_never_boarded_and_400_without_a_reason() throws Exception {
        String gate = staff("rev.gate3", "GATE");
        String ops = staff("rev.ops3", "OPS");
        Trip trip = checkedInTrip("2029-10-03");
        board(trip.priya(), gate).andExpect(jsonPath("$.valid").value(true));

        undo(undoBody(trip.locator(), 0, "   "), ops).andExpect(status().isBadRequest());       // blank: the boarding stays
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarded_passenger WHERE locator = ?", Integer.class, trip.locator())).isEqualTo(1);
        undo(undoBody(trip.locator(), 0, "wrong passenger"), ops).andExpect(status().isNoContent());
        undo(undoBody(trip.locator(), 0, "wrong passenger"), ops).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Not boarded"));
        undo(undoBody(trip.locator(), 1, "wrong passenger"), ops).andExpect(status().isNotFound());   // Raj never boarded

        assertThat(reversals(trip.locator())).isEqualTo(1);
    }

    @Test
    void should_record_exactly_one_reversal_when_two_people_undo_the_same_boarding_at_once() throws Exception {
        String gate = staff("rev.gate4", "GATE");
        String ops = staff("rev.ops4", "OPS");
        String admin = TestStaff.adminBearer(mvc);
        Trip trip = checkedInTrip("2029-10-04");
        board(trip.priya(), gate).andExpect(jsonPath("$.valid").value(true));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (String who : List.of(ops, admin)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return undo(undoBody(trip.locator(), 0, "both of us noticed"), who).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(204, 404);
        } finally {
            pool.shutdownNow();
        }

        assertThat(reversals(trip.locator())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM boarded_passenger WHERE locator = ?", Integer.class, trip.locator())).isZero();
    }
}
