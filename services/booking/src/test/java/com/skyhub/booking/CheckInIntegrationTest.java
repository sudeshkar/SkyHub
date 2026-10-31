package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.application.BoardingPassSigner;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Online check-in on real PostgreSQL through the real filter chain (ADR-0013). Flights SK777 and SK888 leave
 * 30 hours and 30 minutes from now (see TestFlightCatalog), so the window can be tested without moving the clock.
 * Every test uses its own flight date so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class CheckInIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BoardingPassSigner signer;

    @Test
    void should_check_in_and_return_a_boarding_pass_the_gate_can_verify() throws Exception {
        String locator = book("SK501", "2027-02-01", "11D");
        String token = tokenFor(locator);

        String body = mvc.perform(checkIn(locator, token, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passes[0].passengerName").value("PERERA/PRIYA"))
                .andExpect(jsonPath("$.passes[0].seatNumber").value("11D"))
                .andExpect(jsonPath("$.passes[0].sequence").value(1))
                .andReturn().getResponse().getContentAsString();

        assertThat(signer.verify(JsonPath.read(body, "$.passes[0].barcode"))).isTrue();
    }

    @Test
    void should_return_the_same_pass_when_checking_in_twice() throws Exception {
        String locator = book("SK501", "2027-02-02", "11D");
        String token = tokenFor(locator);
        String first = mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(checkIn(locator, token, 0)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((Object) JsonPath.read(second, "$.passes[0].barcode")).isEqualTo(JsonPath.read(first, "$.passes[0].barcode"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in WHERE locator = ?", Integer.class, locator)).isEqualTo(1);
    }

    @Test
    void should_number_boarding_passes_in_order_of_check_in_per_flight() throws Exception {
        String first = book("SK501", "2027-02-03", "11D");
        String second = book("SK501", "2027-02-03", "11E");

        mvc.perform(checkIn(first, tokenFor(first), 0)).andExpect(jsonPath("$.passes[0].sequence").value(1));
        mvc.perform(checkIn(second, tokenFor(second), 0)).andExpect(jsonPath("$.passes[0].sequence").value(2));
    }

    @Test
    void should_give_every_booking_a_different_number_when_many_check_in_at_once() throws Exception {
        int travellers = 8;
        List<String> locators = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < travellers; i++) {
            String locator = book("SK501", "2027-02-04", (20 + i) + "A");
            locators.add(locator);
            tokens.add(tokenFor(locator));
        }
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<Integer>> sequences = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(travellers)) {
            for (int i = 0; i < travellers; i++) {
                int who = i;
                sequences.add(pool.submit(() -> {
                    startingGun.await();
                    String body = mvc.perform(checkIn(locators.get(who), tokens.get(who), 0)).andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    return JsonPath.<Integer>read(body, "$.passes[0].sequence");
                }));
            }
            startingGun.countDown();
            Set<Integer> distinct = new HashSet<>();
            for (Future<Integer> sequence : sequences) {
                distinct.add(sequence.get());
            }
            assertThat(distinct).hasSize(travellers).allMatch(n -> n >= 1 && n <= travellers);
        }
    }

    @Test
    void should_refuse_check_in_before_it_opens_and_after_it_closes() throws Exception {
        String early = book("SK777", "2027-02-05", "11D");
        String late = book("SK888", "2027-02-05", "11D");

        mvc.perform(checkIn(early, tokenFor(early), 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Check-in unavailable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("opens")));
        mvc.perform(checkIn(late, tokenFor(late), 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("closed")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in WHERE locator IN (?, ?)", Integer.class, early, late)).isZero();
    }

    @Test
    void should_freeze_the_seats_of_a_checked_in_flight() throws Exception {
        String locator = book("SK501", "2027-02-06", "11D");
        String token = tokenFor(locator);
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated());

        mvc.perform(put("/api/v1/pnrs/{locator}/seat-assignments", locator).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passengerIndex\":0,\"segmentIndex\":0,\"seatNumber\":\"30C\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Already checked in"));
    }

    @Test
    void should_show_boarding_passes_only_after_check_in_and_only_to_the_owner() throws Exception {
        String mine = book("SK501", "2027-02-07", "11D");
        String other = book("SK501", "2027-02-07", "11E");
        String token = tokenFor(mine);

        mvc.perform(get("/api/v1/pnrs/{locator}/check-ins/0", mine).header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not checked in"));
        mvc.perform(checkIn(mine, token, 0)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/pnrs/{locator}/check-ins/0", mine).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passes[0].seatNumber").value("11D"));

        mvc.perform(get("/api/v1/pnrs/{locator}/check-ins/0", other).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pnrs/{locator}/check-ins", other).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/pnrs/{locator}/check-ins/0", mine)).andExpect(status().isUnauthorized());
    }

    @Test
    void should_not_check_in_a_cancelled_booking_or_show_its_passes() throws Exception {
        String locator = book("SK501", "2027-02-08", "11D");
        String token = tokenFor(locator);
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/pnrs/{locator}/cancellation", locator).header("Authorization", token)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/pnrs/{locator}/check-ins/0", locator).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Booking cancelled"));
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isConflict());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private String book(String flight, String date, String seat) throws Exception {
        String body = """
                {
                  "paymentToken": "tok_visa",
                  "contactEmail": "priya@example.com",
                  "segments": [{"flightNumber": "%s", "departureDate": "%s"}],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%s"]}]
                }
                """.formatted(flight, date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private String tokenFor(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{locator}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    private static MockHttpServletRequestBuilder checkIn(String locator, String token, int segmentIndex) {
        return post("/api/v1/pnrs/{locator}/check-ins", locator).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"segmentIndex\":%d}".formatted(segmentIndex));
    }

    // ---- checking out (ADR-0055) -------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder checkOut(String locator, String token, int segmentIndex) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/pnrs/{locator}/check-ins/{segment}", locator, segmentIndex)
                .header("Authorization", token);
    }

    @Test
    void should_let_the_seat_be_changed_again_after_checking_out_and_check_in_afresh_with_new_numbers() throws Exception {
        String locator = book("SK501", "2029-05-01", "11D");
        String token = tokenFor(locator);
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated()).andExpect(jsonPath("$.passes[0].sequence").value(1));
        String move = "{\"passengerIndex\":0,\"segmentIndex\":0,\"seatNumber\":\"30C\"}";
        mvc.perform(put("/api/v1/pnrs/{l}/seat-assignments", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(move))
                .andExpect(status().isConflict());

        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pnrs/{l}/check-ins/0", locator).header("Authorization", token)).andExpect(status().isNotFound());   // the passes are gone
        mvc.perform(put("/api/v1/pnrs/{l}/seat-assignments", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(move))
                .andExpect(status().isOk());
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.passes[0].seatNumber").value("30C"))
                .andExpect(jsonPath("$.passes[0].sequence").value(2));   // the old pass's number 1 is not reused
    }

    @Test
    void should_answer_204_again_when_there_is_nothing_to_check_out_of() throws Exception {
        String locator = book("SK501", "2029-05-02", "11D");
        String token = tokenFor(locator);

        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated());
        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());
        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());
    }

    @Test
    void should_leave_the_other_flight_of_the_booking_checked_in() throws Exception {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"SK501","departureDate":"2029-05-03"},{"flightNumber":"SK901","departureDate":"2029-05-03"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["12D","12D"]}]}""";
        String locator = JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.locator");
        String token = tokenFor(locator);
        mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated());
        mvc.perform(checkIn(locator, token, 1)).andExpect(status().isCreated());

        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pnrs/{l}/check-ins/1", locator).header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void should_refuse_checking_out_once_check_in_has_closed() throws Exception {
        String late = book("SK888", "2029-05-04", "11D");   // leaves in 30 minutes: check-in is already closed
        jdbc.update("INSERT INTO check_in (locator, segment_index, passenger_index, boarding_sequence, checked_in_at) VALUES (?, 0, 0, 1, now())", late);

        mvc.perform(checkOut(late, tokenFor(late), 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Check-in unavailable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("closed")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in WHERE locator = ?", Integer.class, late)).isEqualTo(1);
    }

    @Test
    void should_check_out_only_for_the_owner_and_not_a_cancelled_booking() throws Exception {
        String mine = book("SK501", "2029-05-05", "11D");
        String other = book("SK501", "2029-05-05", "11E");
        String token = tokenFor(mine);
        mvc.perform(checkIn(mine, token, 0)).andExpect(status().isCreated());

        mvc.perform(checkOut(mine, tokenFor(other), 0)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/pnrs/{l}/check-ins/0", mine))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pnrs/{l}/check-ins/0", mine).header("Authorization", token)).andExpect(status().isOk());   // untouched

        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", mine).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(checkOut(mine, token, 0)).andExpect(status().isConflict());
    }

    @Test
    void should_refuse_a_flight_index_the_booking_does_not_have() throws Exception {
        String locator = book("SK501", "2029-05-06", "11D");

        mvc.perform(checkOut(locator, tokenFor(locator), 3)).andExpect(status().isBadRequest());
    }

    // ---- a gate asks whether a pass is still good (ADR-0056) --------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions verify(String barcode, String authorization) throws Exception {
        var request = post("/api/v1/ops/boarding-passes/verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"barcode\":\"%s\"}".formatted(barcode));
        return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
    }

    @Test
    void should_accept_a_pass_that_is_current_and_refuse_it_once_the_passenger_has_checked_out() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String locator = book("SK501", "2029-05-11", "13D");
        String token = tokenFor(locator);
        String barcode = JsonPath.read(mvc.perform(checkIn(locator, token, 0)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.passes[0].barcode");

        verify(barcode, ops).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.pass.passengerName").value("PERERA/PRIYA"))
                .andExpect(jsonPath("$.pass.seatNumber").value("13D"))
                .andExpect(jsonPath("$.reason").doesNotExist());

        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());

        // The signature on this pass is still genuine (signer.verify), and the gate still refuses it.
        assertThat(signer.verify(barcode)).isTrue();
        verify(barcode, ops).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_CHECKED_IN"))
                .andExpect(jsonPath("$.pass").doesNotExist());   // a refused pass says nothing about who it was for
    }

    @Test
    void should_refuse_the_old_pass_after_a_new_seat_and_check_in_and_accept_the_new_one() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String locator = book("SK501", "2029-05-12", "13D");
        String token = tokenFor(locator);
        String old = JsonPath.read(mvc.perform(checkIn(locator, token, 0)).andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");
        mvc.perform(checkOut(locator, token, 0)).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/pnrs/{l}/seat-assignments", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"passengerIndex\":0,\"segmentIndex\":0,\"seatNumber\":\"30D\"}")).andExpect(status().isOk());
        String fresh = JsonPath.read(mvc.perform(checkIn(locator, token, 0)).andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");

        verify(old, ops).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("SUPERSEDED"));
        verify(fresh, ops).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.pass.seatNumber").value("30D"));
    }

    @Test
    void should_refuse_the_pass_of_a_cancelled_booking() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String locator = book("SK501", "2029-05-13", "13D");
        String token = tokenFor(locator);
        String barcode = JsonPath.read(mvc.perform(checkIn(locator, token, 0)).andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", token)).andExpect(status().isOk());

        verify(barcode, ops).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("BOOKING_CANCELLED"));
    }

    @Test
    void should_refuse_text_that_we_did_not_sign_or_that_was_changed() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String locator = book("SK501", "2029-05-14", "13D");
        String barcode = JsonPath.read(mvc.perform(checkIn(locator, tokenFor(locator), 0)).andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");

        verify(barcode.replace("13D", "1A"), ops).andExpect(jsonPath("$.reason").value("BAD_SIGNATURE"));
        verify("M1PERERA/PRIYA " + locator + " SK501 2029-05-14 13D 0001.forged", ops).andExpect(jsonPath("$.reason").value("BAD_SIGNATURE"));
        verify("x".repeat(400), ops).andExpect(status().isBadRequest());   // longer than the contract allows
    }

    @Test
    void should_be_for_staff_only_not_for_travellers_or_anyone_without_a_token() throws Exception {
        String locator = book("SK501", "2029-05-15", "13D");
        String token = tokenFor(locator);
        String barcode = JsonPath.read(mvc.perform(checkIn(locator, token, 0)).andReturn().getResponse().getContentAsString(), "$.passes[0].barcode");

        verify(barcode, null).andExpect(status().isUnauthorized());
        verify(barcode, "Bearer not-a-token").andExpect(status().isUnauthorized());
        verify(barcode, token).andExpect(status().isForbidden());   // the traveller's own booking token is not a staff token
    }

    // ---- one passenger at a time (ADR-0058) -----------------------------------------------------------------------------

    /** A booking of two passengers (Priya, Raj) on SK501, in seats 15D and 15E. */
    private String bookTwo(String date) throws Exception {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["15D"]},{"firstName":"Raj","lastName":"Perera","seats":["15E"]}]}""".formatted(date);
        return JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.locator");
    }

    private MockHttpServletRequestBuilder checkInWho(String locator, String token, String who) {
        return post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIndex\":0,\"passengerIndexes\":%s}".formatted(who));
    }

    @Test
    void should_check_in_one_passenger_freeze_only_their_seat_and_leave_the_other_free_to_move() throws Exception {
        String locator = bookTwo("2029-07-01");
        String token = tokenFor(locator);

        mvc.perform(checkInWho(locator, token, "[1]")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.passes.length()").value(1))
                .andExpect(jsonPath("$.passes[0].passengerName").value("PERERA/RAJ"))
                .andExpect(jsonPath("$.passes[0].passengerIndex").value(1));

        String moveRaj = "{\"passengerIndex\":1,\"segmentIndex\":0,\"seatNumber\":\"31C\"}";
        String movePriya = "{\"passengerIndex\":0,\"segmentIndex\":0,\"seatNumber\":\"31D\"}";
        mvc.perform(put("/api/v1/pnrs/{l}/seat-assignments", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(moveRaj))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Already checked in"));
        mvc.perform(put("/api/v1/pnrs/{l}/seat-assignments", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(movePriya))
                .andExpect(status().isOk());   // Priya is not checked in
    }

    @Test
    void should_check_in_the_rest_later_without_renumbering_and_show_everyone_who_is_in() throws Exception {
        String locator = bookTwo("2029-07-02");
        String token = tokenFor(locator);
        int rajNumber = JsonPath.read(mvc.perform(checkInWho(locator, token, "[1]")).andReturn().getResponse().getContentAsString(), "$.passes[0].sequence");

        String rest = mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIndex\":0}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<Integer>>read(rest, "$.passes[*].passengerIndex")).containsExactly(0, 1);
        assertThat(JsonPath.<Integer>read(rest, "$.passes[1].sequence")).isEqualTo(rajNumber);   // Raj keeps his number
        assertThat(JsonPath.<Integer>read(rest, "$.passes[0].sequence")).isNotEqualTo(rajNumber);
        mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIndex\":0}")).andExpect(status().isOk());   // everyone is in already: the same passes
    }

    @Test
    void should_check_out_one_passenger_and_refuse_only_their_pass_at_the_gate() throws Exception {
        String ops = TestStaff.adminBearer(mvc);
        String locator = bookTwo("2029-07-03");
        String token = tokenFor(locator);
        String both = mvc.perform(post("/api/v1/pnrs/{l}/check-ins", locator).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"segmentIndex\":0}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String priyaPass = JsonPath.read(both, "$.passes[0].barcode");
        String rajPass = JsonPath.read(both, "$.passes[1].barcode");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/pnrs/{l}/check-ins/0", locator)
                .param("passengerIndex", "0").header("Authorization", token)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pnrs/{l}/check-ins/0", locator).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passes.length()").value(1)).andExpect(jsonPath("$.passes[0].passengerIndex").value(1));
        verify(priyaPass, ops).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("NOT_CHECKED_IN"));
        verify(rajPass, ops).andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void should_refuse_a_passenger_who_is_not_on_the_booking() throws Exception {
        String locator = bookTwo("2029-07-04");
        String token = tokenFor(locator);

        mvc.perform(checkInWho(locator, token, "[2]")).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/pnrs/{l}/check-ins/0", locator)
                .param("passengerIndex", "2").header("Authorization", token)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM check_in WHERE locator = ?", Integer.class, locator)).isZero();
    }
}
