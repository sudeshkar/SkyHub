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
}
