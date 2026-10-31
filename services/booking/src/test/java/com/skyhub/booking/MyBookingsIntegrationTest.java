package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.application.ContactEmailIndexBackfill;
import com.skyhub.booking.persistence.PiiCipher;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * A signed-in traveller sees, and can open, exactly the bookings made with their address (ADR-0034), on real PostgreSQL through
 * the real filter chain. Every test uses its own address and flight date, so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class MyBookingsIntegrationTest {

    private static final String PASSWORD = "a long enough passphrase";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PiiCipher cipher;

    @Autowired
    private ContactEmailIndexBackfill backfill;

    @Autowired
    private com.skyhub.booking.application.BookingsByAddress byAddress;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_list_only_the_bookings_made_with_my_address_whatever_its_case() throws Exception {
        String mine = book("Mine.One@Example.com", "2026-12-10", "11D");
        String also = book("mine.one@example.com", "2026-12-11", "11D");
        book("someone.else@example.com", "2026-12-12", "11D");
        String bearer = signedIn("mine.one@example.com");

        String response = mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<String>>read(response, "$.bookings[*].locator")).containsExactly(also, mine);   // newest first
        assertThat(JsonPath.<Integer>read(response, "$.bookings[0].passengerCount")).isEqualTo(1);
        assertThat(JsonPath.<String>read(response, "$.bookings[0].segments[0].flightNumber")).isEqualTo("SK501");
        assertThat(response).doesNotContain("Perera").doesNotContain("example.com").doesNotContain("11D");
    }

    @Test
    void should_show_a_cancelled_booking_as_cancelled() throws Exception {
        String locator = book("cancelled.one@example.com", "2026-12-13", "11D");
        String manage = manageToken(locator);
        mvc.perform(post("/api/v1/pnrs/{locator}/cancellation", locator).header("Authorization", manage)).andExpect(status().isOk());

        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", signedIn("cancelled.one@example.com")))
                .andExpect(jsonPath("$.bookings[0].status").value("CANCELLED"));
    }

    @Test
    void should_list_nothing_for_an_address_that_never_booked() throws Exception {
        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", signedIn("never.booked@example.com")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookings").isEmpty()).andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void should_open_my_booking_without_its_reference_last_name_and_use_the_token_for_that_booking_only() throws Exception {
        String mine = book("opens.mine@example.com", "2026-12-14", "11D");
        String other = book("opens.other@example.com", "2026-12-14", "12D");
        String bearer = signedIn("opens.mine@example.com");

        String response = mvc.perform(post("/api/v1/accounts/me/bookings/{locator}/sessions", mine).header("Authorization", bearer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pnr.locator").value(mine))
                .andReturn().getResponse().getContentAsString();
        String manage = "Bearer " + JsonPath.<String>read(response, "$.accessToken");

        mvc.perform(get("/api/v1/pnrs/{locator}", mine).header("Authorization", manage)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/pnrs/{locator}", other).header("Authorization", manage)).andExpect(status().isForbidden());
    }

    @Test
    void should_answer_someone_elses_booking_exactly_like_one_that_does_not_exist() throws Exception {
        String theirs = book("theirs@example.com", "2026-12-15", "11D");
        String bearer = signedIn("not.theirs@example.com");

        String foreign = mvc.perform(post("/api/v1/accounts/me/bookings/{locator}/sessions", theirs).header("Authorization", bearer))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/v1/accounts/me/bookings/ZZZZZZ/sessions").header("Authorization", bearer))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        // Only "instance" differs: it echoes the reference the caller typed, which tells them nothing.
        for (String field : List.of("$.detail", "$.title", "$.status")) {
            assertThat((Object) JsonPath.read(foreign, field)).isEqualTo(JsonPath.read(unknown, field));
        }
    }

    @Test
    void should_keep_the_address_out_of_the_index() throws Exception {
        String locator = book("indexed.safely@example.com", "2026-12-16", "11D");

        String stored = jdbc.queryForObject("SELECT contact_email_index FROM pnr WHERE locator = ?", String.class, locator);

        assertThat(stored).isEqualTo(cipher.blindIndex("indexed.safely@example.com")).hasSize(64).doesNotContain("indexed");
    }

    @Test
    void should_find_old_bookings_once_the_backfill_has_indexed_them() throws Exception {
        String legacy = book("legacy.one@example.com", "2026-12-17", "11D");
        jdbc.update("UPDATE pnr SET contact_email_index = NULL WHERE locator = ?", legacy);   // as every booking was before this slice
        String bearer = signedIn("legacy.one@example.com");

        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", bearer)).andExpect(jsonPath("$.bookings").isEmpty());
        mvc.perform(post("/api/v1/accounts/me/bookings/{locator}/sessions", legacy).header("Authorization", bearer))
                .andExpect(status().isNotFound());

        assertThat(backfill.run()).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", bearer))
                .andExpect(jsonPath("$.bookings[0].locator").value(legacy));
        assertThat(backfill.run()).isZero();    // nothing left to do, so a normal start writes nothing
    }

    @Test
    void should_walk_every_booking_once_in_pages_even_when_two_were_made_in_the_same_instant() throws Exception {
        String email = "pages.walker@example.com";
        List<String> made = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            made.add(book(email, "2026-12-2" + i, "11D"));
        }
        // Two bookings with exactly the same creation time: the reference must break the tie, or one could be skipped or shown twice.
        jdbc.update("UPDATE pnr SET created_at = (SELECT created_at FROM pnr WHERE locator = ?) WHERE locator = ?", made.get(3), made.get(2));
        String index = cipher.blindIndex(email);

        List<String> seen = new java.util.ArrayList<>();
        com.skyhub.booking.application.BookingCursor after = null;
        int pages = 0;
        while (true) {
            var page = byAddress.newestAfter(index, after, 3);   // pages of two, plus the one extra that says there is more
            List<String> shown = page.stream().limit(2).map(com.skyhub.booking.application.view.BookingSummary::locator).toList();
            seen.addAll(shown);
            pages++;
            if (page.size() <= 2) {
                break;
            }
            var last = page.get(1);
            after = new com.skyhub.booking.application.BookingCursor(last.createdAt(), last.locator());
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(made);
        // Newest first overall, and for the two made together, the higher reference first.
        var times = seen.stream().map(l -> jdbc.queryForObject("SELECT created_at FROM pnr WHERE locator = ?", java.sql.Timestamp.class, l)).toList();
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        int tied = seen.indexOf(made.get(2).compareTo(made.get(3)) > 0 ? made.get(2) : made.get(3));
        assertThat(tied).isLessThan(seen.indexOf(made.get(2).compareTo(made.get(3)) > 0 ? made.get(3) : made.get(2)));
    }

    @Test
    void should_answer_a_page_through_http_and_refuse_a_cursor_that_is_not_one() throws Exception {
        book("http.pages@example.com", "2026-12-30", "11D");
        String bearer = signedIn("http.pages@example.com");

        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.truncated").value(false)).andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/api/v1/accounts/me/bookings").param("cursor", "forged").header("Authorization", bearer))
                .andExpect(status().isBadRequest());
        // A cursor past the oldest booking is simply an empty last page.
        String past = new com.skyhub.booking.application.BookingCursor(java.time.Instant.parse("2000-01-01T00:00:00Z"), "AAAAAA").encode();
        mvc.perform(get("/api/v1/accounts/me/bookings").param("cursor", past).header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookings").isEmpty());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private String book(String email, String date, String seat) throws Exception {
        String body = """
                {
                  "paymentToken": "tok_visa",
                  "contactEmail": "%s",
                  "segments": [{"flightNumber": "SK501", "departureDate": "%s"}],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%s"]}]
                }
                """.formatted(email, date, seat);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    private String manageToken(String locator) throws Exception {
        String response = mvc.perform(post("/api/v1/pnrs/{locator}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    private MockHttpServletRequestBuilder body(String path, String content) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    /** Signs up the way a person does (emailed link, then a password) and signs in. */
    private String signedIn(String email) throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
        String token = null;
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = 'account.password-link' ORDER BY seq DESC LIMIT 50", String.class)) {
            var event = json.readTree(cipher.decrypt(payload));
            if (email.equalsIgnoreCase(event.get("contactEmail").asString())) {
                token = event.get("token").asString();
                break;
            }
        }
        assertThat(token).as("a link was queued for " + email).isNotNull();
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, PASSWORD)))
                .andExpect(status().isNoContent());
        String response = mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }
}
