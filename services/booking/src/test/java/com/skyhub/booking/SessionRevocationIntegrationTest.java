package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.persistence.PiiCipher;
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
 * Credentials changing ends every open session (ADR-0041), on real PostgreSQL through the real filter chain: a token that was fine a
 * moment ago is refused on its very next request. Every test uses its own addresses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class SessionRevocationIntegrationTest {

    private static final String PASSWORD = "a long enough passphrase";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PiiCipher cipher;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_refuse_an_open_session_the_moment_the_address_is_moved_and_let_a_new_sign_in_through() throws Exception {
        String open = signedIn("revoke.addr@example.com");
        String other = signedIn("revoke.bystander@example.com");
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", open)).andExpect(status().isOk());

        mvc.perform(body("/api/v1/accounts/me/email-change", "{\"newEmail\":\"revoke.addr.new@example.com\",\"password\":\"%s\"}".formatted(PASSWORD))
                .header("Authorization", open)).andExpect(status().isAccepted());
        String link = latestToken("account.email-change-link", "revoke.addr.new@example.com");
        mvc.perform(body("/api/v1/accounts/me/email-change/confirmation", "{\"token\":\"%s\"}".formatted(link)).header("Authorization", open))
                .andExpect(status().isNoContent());   // the session that made the change is the last thing it does

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", open)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", open)).andExpect(status().isUnauthorized());
        mvc.perform(body("/api/v1/accounts/me/email-change", "{\"newEmail\":\"x.y@example.com\",\"password\":\"%s\"}".formatted(PASSWORD))
                .header("Authorization", open)).andExpect(status().isUnauthorized());
        String fresh = signIn("revoke.addr.new@example.com", PASSWORD);
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", fresh))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("revoke.addr.new@example.com"));
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", other)).andExpect(status().isOk());   // nobody else is signed out
    }

    @Test
    void should_refuse_an_open_session_the_moment_a_password_is_chosen_with_an_emailed_link() throws Exception {
        String stolen = signedIn("revoke.pw@example.com");
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", stolen)).andExpect(status().isOk());
        jdbc.update("UPDATE traveller_account SET link_issued_at = now() - interval '5 minutes' WHERE email_index = ?", cipher.blindIndex("revoke.pw@example.com"));

        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"revoke.pw@example.com\"}")).andExpect(status().isAccepted());
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"a brand new passphrase\"}"
                .formatted(latestToken("account.password-link", "revoke.pw@example.com")))).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", stolen)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", signIn("revoke.pw@example.com", "a brand new passphrase")))
                .andExpect(status().isOk());
    }

    @Test
    void should_not_end_a_session_for_things_that_are_not_a_change_of_credentials() throws Exception {
        String bearer = signedIn("revoke.none@example.com");

        mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"revoke.none@example.com\",\"password\":\"wrong wrong wrong\"}"))
                .andExpect(status().isUnauthorized());                                  // a failed sign-in
        mvc.perform(body("/api/v1/accounts/me/email-change", "{\"newEmail\":\"revoke.none.new@example.com\",\"password\":\"%s\"}".formatted(PASSWORD))
                .header("Authorization", bearer)).andExpect(status().isAccepted());     // asking, without confirming
        signIn("revoke.none@example.com", PASSWORD);                                    // another sign-in on another device

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void should_leave_a_booking_token_alone_since_it_is_a_separate_credential_for_one_booking() throws Exception {
        book("revoke.booking@example.com", "2028-04-01");
        String bearer = signedIn("revoke.booking@example.com");
        String locator = JsonPath.<String>read(mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", bearer))
                .andReturn().getResponse().getContentAsString(), "$.bookings[0].locator");
        String manage = "Bearer " + JsonPath.<String>read(mvc.perform(post("/api/v1/accounts/me/bookings/{l}/sessions", locator).header("Authorization", bearer))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.accessToken");

        jdbc.update("UPDATE traveller_account SET session_epoch = session_epoch + 1 WHERE email_index = ?", cipher.blindIndex("revoke.booking@example.com"));

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/pnrs/{l}", locator).header("Authorization", manage)).andExpect(status().isOk());   // up to its own 15 minutes
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() throws Exception {
        String bearer = signedIn("revoke.gone@example.com");
        jdbc.update("DELETE FROM traveller_account WHERE email_index = ?", cipher.blindIndex("revoke.gone@example.com"));

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder body(String path, String content) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private ResultActions signInRequest(String email, String password) throws Exception {
        return mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String signIn(String email, String password) throws Exception {
        String response = signInRequest(email, password).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    /** The token from the newest event of this type queued for this address, read the way the notification service would. */
    private String latestToken(String eventType, String email) {
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ? ORDER BY seq DESC LIMIT 100", String.class, eventType)) {
            var event = json.readTree(cipher.decrypt(payload));
            if (email.equalsIgnoreCase(event.get("contactEmail").asString())) {
                return TestLinkTokens.tokenOf(event);
            }
        }
        throw new AssertionError("No " + eventType + " was queued for " + email);
    }

    /** Signs up the way a person does (emailed link, then a password) and signs in. */
    private String signedIn(String email) throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(latestToken("account.password-link", email), PASSWORD)))
                .andExpect(status().isNoContent());
        return signIn(email, PASSWORD);
    }

    private String book(String email, String date) throws Exception {
        String bookingBody = """
                {"paymentToken":"tok_visa","contactEmail":"%s",
                 "segments":[{"flightNumber":"SK501","departureDate":"%s"}],
                 "passengers":[{"firstName":"Priya","lastName":"Perera","seats":["11D"]}]}""".formatted(email, date);
        return JsonPath.read(mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(bookingBody)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.locator");
    }

    @Test
    void should_end_every_session_of_the_account_on_request_and_let_a_fresh_sign_in_through() throws Exception {
        String here = signedIn("signout.all@example.com");
        String elsewhere = signIn("signout.all@example.com", PASSWORD);   // the same person on another device
        String bystander = signedIn("signout.bystander@example.com");
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", elsewhere)).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/accounts/me/sessions").header("Authorization", here))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", here)).andExpect(status().isUnauthorized());       // the one that asked
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", elsewhere)).andExpect(status().isUnauthorized());   // and the other device
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bystander)).andExpect(status().isOk());            // nobody else
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", signIn("signout.all@example.com", PASSWORD)))
                .andExpect(status().isOk());   // the password and the address are untouched: signing in again just works
    }

    @Test
    void should_not_need_a_password_or_change_anything_but_the_sessions() throws Exception {
        String bearer = signedIn("signout.nothing.else@example.com");

        mvc.perform(delete("/api/v1/accounts/me/sessions").header("Authorization", bearer)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/accounts/me").header("Authorization", signIn("signout.nothing.else@example.com", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("signout.nothing.else@example.com"));
    }

    @Test
    void should_refuse_it_without_an_account_token() throws Exception {
        String bearer = signedIn("signout.auth@example.com");
        String locator = book("signout.auth@example.com", "2028-05-01");
        String manage = "Bearer " + JsonPath.<String>read(mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"Perera\"}")).andReturn().getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(delete("/api/v1/accounts/me/sessions")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/accounts/me/sessions").header("Authorization", manage)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer)).andExpect(status().isOk());   // and nothing was ended
    }
}
