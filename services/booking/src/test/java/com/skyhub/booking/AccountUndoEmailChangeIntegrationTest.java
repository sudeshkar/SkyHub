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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Taking an account back after it was moved without its owner (ADR-0054), on real PostgreSQL through the real filter chain. The story:
 * somebody who knows the password moves the account to an address they control; the owner of the old address uses the link in the
 * notice. Every test uses its own addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class AccountUndoEmailChangeIntegrationTest {

    private static final String PASSWORD = "a long enough passphrase";
    private static final String NEW_PASSWORD = "a brand new passphrase";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PiiCipher cipher;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_give_the_account_back_to_the_owner_of_the_old_address_and_shut_the_other_person_out() throws Exception {
        String booking = book("owner.one@example.com", "2028-04-01");
        String intruder = signedIn("owner.one@example.com");   // knows the password
        moveTo(intruder, "intruder.one@example.com");
        String intruderSession = signInBearer("intruder.one@example.com");

        undo(undoToken("owner.one@example.com")).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", intruderSession)).andExpect(status().isUnauthorized());   // sessions ended
        signIn("intruder.one@example.com", PASSWORD).andExpect(status().isUnauthorized());   // no longer theirs
        signIn("owner.one@example.com", PASSWORD).andExpect(status().isUnauthorized());      // and the password they knew is gone
        // The owner chooses a new password through the link sent to the restored address (and only that link works).
        String link = latestToken("account.password-link", "owner.one@example.com");
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(link, NEW_PASSWORD)))
                .andExpect(status().isNoContent());
        String back = bearerOf(signIn("owner.one@example.com", NEW_PASSWORD).andExpect(status().isCreated()));
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", back)).andExpect(jsonPath("$.email").value("owner.one@example.com"));
        String list = mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", back)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$.bookings[*].locator")).contains(booking);   // the bookings made with the old address are back
    }

    @Test
    void should_work_once_and_only_with_the_token_from_the_notice() throws Exception {
        String intruder = signedIn("owner.two@example.com");
        moveTo(intruder, "intruder.two@example.com");
        String token = undoToken("owner.two@example.com");

        undo("not-the-token").andExpect(status().isBadRequest());
        undo("").andExpect(status().isBadRequest());
        undo(token).andExpect(status().isNoContent());
        undo(token).andExpect(status().isBadRequest());   // used
    }

    @Test
    void should_refuse_it_after_seven_days() throws Exception {
        String intruder = signedIn("owner.three@example.com");
        moveTo(intruder, "intruder.three@example.com");
        String token = undoToken("owner.three@example.com");
        jdbc.update("UPDATE traveller_account SET undo_expires_at = now() - interval '1 minute' WHERE email_index = ?",
                cipher.blindIndex("intruder.three@example.com"));

        undo(token).andExpect(status().isBadRequest());

        signIn("intruder.three@example.com", PASSWORD).andExpect(status().isCreated());   // still moved
    }

    @Test
    void should_answer_400_and_change_nothing_when_another_account_has_taken_the_old_address_since() throws Exception {
        String intruder = signedIn("owner.four@example.com");
        moveTo(intruder, "intruder.four@example.com");
        String token = undoToken("owner.four@example.com");
        signedIn("owner.four@example.com");   // somebody signs up with the freed address first

        undo(token).andExpect(status().isBadRequest());

        signIn("intruder.four@example.com", PASSWORD).andExpect(status().isCreated());   // the move stands
    }

    @Test
    void should_undo_only_the_latest_move() throws Exception {
        String first = signedIn("owner.five@example.com");
        moveTo(first, "step.one@example.com");
        String firstUndo = undoToken("owner.five@example.com");
        moveTo(signInBearer("step.one@example.com"), "step.two@example.com");

        undo(firstUndo).andExpect(status().isBadRequest());   // replaced by the later move's token
        undo(undoToken("step.one@example.com")).andExpect(status().isNoContent());
        signIn("step.one@example.com", PASSWORD).andExpect(status().isUnauthorized());   // password thrown away
        assertThat(eventsFor("account.password-link", "step.one@example.com")).isEqualTo(1);   // back at the address it had before the latest move
    }

    @Test
    void should_keep_the_old_address_encrypted_and_the_token_as_a_hash_at_rest() throws Exception {
        String intruder = signedIn("owner.six@example.com");
        moveTo(intruder, "intruder.six@example.com");
        String token = undoToken("owner.six@example.com");

        String undoEmail = jdbc.queryForObject("SELECT undo_email FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("intruder.six@example.com"));
        String hash = jdbc.queryForObject("SELECT undo_token_hash FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("intruder.six@example.com"));

        assertThat(undoEmail).startsWith("v1:").doesNotContain("owner.six");
        assertThat(hash).hasSize(64).isNotEqualTo(token);
        undo(token).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT undo_token_hash FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("owner.six@example.com"))).as("spent").isNull();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder body(String path, String content) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private ResultActions undo(String token) throws Exception {
        return mvc.perform(body("/api/v1/accounts/email-change-reversals", "{\"token\":\"%s\"}".formatted(token)));   // no Authorization header
    }

    /** What the intruder does: password, link to an address they control, confirm. */
    private void moveTo(String bearer, String newEmail) throws Exception {
        mvc.perform(body("/api/v1/accounts/me/email-change", "{\"newEmail\":\"%s\",\"password\":\"%s\"}".formatted(newEmail, PASSWORD))
                .header("Authorization", bearer)).andExpect(status().isAccepted());
        mvc.perform(body("/api/v1/accounts/me/email-change/confirmation", "{\"token\":\"%s\"}".formatted(latestToken("account.email-change-link", newEmail)))
                .header("Authorization", bearer)).andExpect(status().isNoContent());
    }

    /** The undo token from the newest notice sent to this (old) address, read the way the notification service would. */
    private String undoToken(String oldEmail) {
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = 'account.email-changed' ORDER BY seq DESC LIMIT 100", String.class)) {
            var event = json.readTree(cipher.decrypt(payload));
            if (oldEmail.equalsIgnoreCase(event.get("contactEmail").asString())) {
                return TestLinkTokens.tokenOf(event, "sealedUndoToken");
            }
        }
        throw new AssertionError("No notice was queued for " + oldEmail);
    }

    private String latestToken(String eventType, String email) {
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ? ORDER BY seq DESC LIMIT 100", String.class, eventType)) {
            var event = json.readTree(cipher.decrypt(payload));
            if (email.equalsIgnoreCase(event.get("contactEmail").asString())) {
                return TestLinkTokens.tokenOf(event);
            }
        }
        throw new AssertionError("No " + eventType + " was queued for " + email);
    }

    private long eventsFor(String eventType, String email) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ?", String.class, eventType).stream()
                .filter(p -> email.equalsIgnoreCase(json.readTree(cipher.decrypt(p)).get("contactEmail").asString())).count();
    }

    private ResultActions signIn(String email, String password) throws Exception {
        return mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String signInBearer(String email) throws Exception {
        return bearerOf(signIn(email, PASSWORD).andExpect(status().isCreated()));
    }

    private static String bearerOf(ResultActions signedIn) throws Exception {
        return "Bearer " + JsonPath.<String>read(signedIn.andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    /** Signs up the way a person does (emailed link, then a password) and signs in. */
    private String signedIn(String email) throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(latestToken("account.password-link", email), PASSWORD)))
                .andExpect(status().isNoContent());
        return signInBearer(email);
    }

    private String book(String email, String date) throws Exception {
        String bookingBody = """
                {"paymentToken":"tok_visa","contactEmail":"%s",
                 "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["22D"]}]}""".formatted(email, date);
        String response = mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(bookingBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.locator");
    }
}
