package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
 * Moving an account to another address (ADR-0039), on real PostgreSQL through the real filter chain: the password, the link to the
 * new address, the change, and what must not be possible. Every test uses its own addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class AccountEmailChangeIntegrationTest {

    private static final String PASSWORD = "a long enough passphrase";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PiiCipher cipher;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_move_the_account_after_the_new_address_is_proved_and_the_bookings_follow_the_address() throws Exception {
        String oldBooking = book("mover.old@example.com", "2028-03-01");
        String bearer = signedIn("mover.old@example.com");

        ask(bearer, "mover.new@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken("account.email-change-link", "mover.new@example.com");
        confirm(bearer, token).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("mover.new@example.com"));
        signIn("mover.new@example.com", PASSWORD).andExpect(status().isCreated());
        signIn("mover.old@example.com", PASSWORD).andExpect(status().isUnauthorized());   // the old address no longer opens the account

        String newBooking = book("mover.new@example.com", "2028-03-02");
        String list = mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$.bookings[*].locator")).containsExactly(newBooking);
        assertThat(list).doesNotContain(oldBooking);
        mvc.perform(post("/api/v1/accounts/me/bookings/{l}/sessions", oldBooking).header("Authorization", bearer)).andExpect(status().isNotFound());
    }

    @Test
    void should_change_nothing_until_the_link_is_used_and_keep_the_pending_address_encrypted_and_the_token_as_a_hash() throws Exception {
        String bearer = signedIn("pending.old@example.com");

        ask(bearer, "pending.new@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken("account.email-change-link", "pending.new@example.com");

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(jsonPath("$.email").value("pending.old@example.com"));
        signIn("pending.new@example.com", PASSWORD).andExpect(status().isUnauthorized());   // not moved yet
        String stored = jdbc.queryForObject("SELECT pending_email FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("pending.old@example.com"));
        String tokenHash = jdbc.queryForObject("SELECT change_token_hash FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("pending.old@example.com"));
        assertThat(stored).startsWith("v1:").doesNotContain("pending.new");
        assertThat(tokenHash).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void should_refuse_a_wrong_password_with_403_and_remember_the_failures_so_the_lock_holds() throws Exception {
        String bearer = signedIn("locked.out@example.com");

        for (int i = 0; i < 5; i++) {
            ask(bearer, "locked.new@example.com", "wrong wrong wrong").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Password not accepted"));
        }

        ask(bearer, "locked.new@example.com", PASSWORD).andExpect(status().isForbidden());   // locked: even the right password
        signIn("locked.out@example.com", PASSWORD).andExpect(status().isUnauthorized());   // the same lock holds at sign-in
        assertThat(eventsFor("account.email-change-link", "locked.new@example.com")).isZero();
    }

    @Test
    void should_answer_the_same_and_send_nothing_when_the_new_address_already_has_an_account() throws Exception {
        signedIn("already.here@example.com");
        String bearer = signedIn("wants.to.move@example.com");

        ask(bearer, "already.here@example.com", PASSWORD).andExpect(status().isAccepted());

        assertThat(eventsFor("account.email-change-link", "already.here@example.com")).isZero();
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(jsonPath("$.email").value("wants.to.move@example.com"));
    }

    @Test
    void should_refuse_the_address_the_account_already_has() throws Exception {
        String bearer = signedIn("same.address@example.com");

        ask(bearer, "Same.Address@Example.com", PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void should_kill_a_password_reset_link_that_was_already_out_to_the_old_address() throws Exception {
        String bearer = signedIn("reset.before@example.com");
        jdbc.update("UPDATE traveller_account SET link_issued_at = now() - interval '5 minutes' WHERE email_index = ?",
                cipher.blindIndex("reset.before@example.com"));
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"reset.before@example.com\"}")).andExpect(status().isAccepted());
        String resetToken = latestToken("account.password-link", "reset.before@example.com");

        ask(bearer, "reset.after@example.com", PASSWORD).andExpect(status().isAccepted());
        confirm(bearer, latestToken("account.email-change-link", "reset.after@example.com")).andExpect(status().isNoContent());

        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"an attackers new passphrase\"}".formatted(resetToken)))
                .andExpect(status().isBadRequest());
        signIn("reset.after@example.com", PASSWORD).andExpect(status().isCreated());   // the real owner's password still stands
    }

    @Test
    void should_not_let_another_account_spend_a_link_it_was_not_sent() throws Exception {
        String owner = signedIn("link.owner@example.com");
        String other = signedIn("link.other@example.com");
        ask(owner, "link.owner.new@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken("account.email-change-link", "link.owner.new@example.com");

        confirm(other, token).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", other)).andExpect(jsonPath("$.email").value("link.other@example.com"));
        confirm(owner, token).andExpect(status().isNoContent());   // and it still works for the account it was sent for
    }

    @Test
    void should_work_only_once_and_not_send_a_second_link_within_two_minutes() throws Exception {
        String bearer = signedIn("once.old@example.com");
        ask(bearer, "once.new@example.com", PASSWORD).andExpect(status().isAccepted());
        ask(bearer, "once.new@example.com", PASSWORD).andExpect(status().isAccepted());   // straight away again
        assertThat(eventsFor("account.email-change-link", "once.new@example.com")).isEqualTo(1);
        String token = latestToken("account.email-change-link", "once.new@example.com");

        confirm(bearer, token).andExpect(status().isNoContent());

        confirm(bearer, token).andExpect(status().isBadRequest());
    }

    @Test
    void should_answer_400_when_another_account_took_the_new_address_between_asking_and_confirming() throws Exception {
        String bearer = signedIn("race.old@example.com");
        ask(bearer, "race.new@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken("account.email-change-link", "race.new@example.com");
        signedIn("race.new@example.com");   // somebody else signs up with it first

        confirm(bearer, token).andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(jsonPath("$.email").value("race.old@example.com"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder body(String path, String content) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private org.springframework.test.web.servlet.ResultActions ask(String bearer, String newEmail, String password) throws Exception {
        return mvc.perform(body("/api/v1/accounts/me/email-change", "{\"newEmail\":\"%s\",\"password\":\"%s\"}".formatted(newEmail, password))
                .header("Authorization", bearer));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String bearer, String token) throws Exception {
        return mvc.perform(body("/api/v1/accounts/me/email-change/confirmation", "{\"token\":\"%s\"}".formatted(token)).header("Authorization", bearer));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String email, String password) throws Exception {
        return mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    /** The token from the newest event of this type queued for this address, read the way the notification service would. */
    private String latestToken(String eventType, String email) {
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ? ORDER BY seq DESC LIMIT 100", String.class, eventType)) {
            var event = json.readTree(cipher.decrypt(payload));
            if (email.equalsIgnoreCase(event.get("contactEmail").asString())) {
                return event.get("token").asString();
            }
        }
        throw new AssertionError("No " + eventType + " was queued for " + email);
    }

    private long eventsFor(String eventType, String email) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ?", String.class, eventType).stream()
                .filter(p -> email.equalsIgnoreCase(json.readTree(cipher.decrypt(p)).get("contactEmail").asString())).count();
    }

    /** Signs up the way a person does (emailed link, then a password) and signs in. */
    private String signedIn(String email) throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(latestToken("account.password-link", email), PASSWORD)))
                .andExpect(status().isNoContent());
        String response = signIn(email, PASSWORD).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    private String book(String email, String date) throws Exception {
        String bookingBody = """
                {"paymentToken":"tok_visa","contactEmail":"%s",
                 "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["11D"]}]}""".formatted(email, date);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(bookingBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }

    @Test
    void should_queue_one_notice_for_the_old_address_with_the_change_and_none_when_it_is_refused() throws Exception {
        String bearer = signedIn("notice.old@example.com");
        ask(bearer, "notice.new@example.com", PASSWORD).andExpect(status().isAccepted());
        String token = latestToken("account.email-change-link", "notice.new@example.com");

        confirm(bearer, "wrong-token").andExpect(status().isBadRequest());
        assertThat(eventsFor("account.email-changed", "notice.old@example.com")).isZero();   // a refused change tells nobody

        confirm(bearer, token).andExpect(status().isNoContent());
        confirm(bearer, token).andExpect(status().isBadRequest());   // used: no second notice

        assertThat(eventsFor("account.email-changed", "notice.old@example.com")).isEqualTo(1);
        assertThat(eventsFor("account.email-changed", "notice.new@example.com")).isZero();   // never to the new address
        String payload = jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = 'account.email-changed' ORDER BY seq DESC LIMIT 1", String.class)
                .getFirst();
        String clear = cipher.decrypt(payload);
        assertThat(clear).contains("n****@example.com").doesNotContain("notice.new@example.com").doesNotContain("token");
    }
}
