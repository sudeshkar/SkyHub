package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * Signing out of a booking ends every open session of it (ADR-0057), on real PostgreSQL through the real filter chain: a token copied
 * before the sign-out is refused on its very next request. Every test uses its own flight date and seats.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class BookingSessionRevocationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void should_refuse_every_token_of_the_booking_after_signing_out_including_a_copy_and_let_a_new_session_in() throws Exception {
        String locator = book("2029-06-01", "14D");
        String mine = tokenFor(locator);
        String copied = tokenFor(locator);   // what somebody who saw the cookie holds
        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", copied)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", locator).header("Authorization", mine)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", mine)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", copied)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", copied)).andExpect(status().isUnauthorized());
        String fresh = tokenFor(locator);   // proving the last name again opens a new session
        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", fresh)).andExpect(status().isOk());
    }

    @Test
    void should_leave_other_bookings_signed_in() throws Exception {
        String mine = book("2029-06-02", "14D");
        String other = book("2029-06-02", "14E");
        String mineToken = tokenFor(mine);
        String otherToken = tokenFor(other);

        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", mine).header("Authorization", mineToken)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/pnrs/{l}", other).header("Authorization", otherToken)).andExpect(status().isOk());
    }

    @Test
    void should_end_sessions_only_for_the_booking_the_token_is_for_and_only_with_a_token() throws Exception {
        String mine = book("2029-06-03", "14D");
        String other = book("2029-06-03", "14E");
        String token = tokenFor(mine);

        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", other).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", mine)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", mine).header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/pnrs/{l}", mine).header("Authorization", token)).andExpect(status().isOk());   // nothing was ended
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_session_epoch WHERE locator IN (?, ?)", Integer.class, mine, other)).isZero();
    }

    @Test
    void should_count_each_sign_out_as_one_step_and_keep_the_count_per_booking() throws Exception {
        String locator = book("2029-06-04", "14D");
        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", locator).header("Authorization", tokenFor(locator))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", locator).header("Authorization", tokenFor(locator))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT epoch FROM booking_session_epoch WHERE locator = ?", Integer.class, locator)).isEqualTo(2);
    }

    @Test
    void should_keep_a_session_issued_after_the_sign_out_usable_until_the_next_one() throws Exception {
        String locator = book("2029-06-05", "14D");
        mvc.perform(delete("/api/v1/pnrs/{l}/sessions", locator).header("Authorization", tokenFor(locator))).andExpect(status().isNoContent());
        String after = tokenFor(locator);

        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", after)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", after)).andExpect(status().isOk());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private String book(String date, String seat) throws Exception {
        String body = """
                {"paymentToken":"tok_visa","contactEmail":"priya@example.com",
                 "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["%s"]}]}""".formatted(date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private String tokenFor(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{locator}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }
}
