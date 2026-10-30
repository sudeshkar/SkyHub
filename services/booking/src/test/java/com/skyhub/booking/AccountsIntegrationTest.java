package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.persistence.PiiCipher;
import java.util.List;
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

/** Sign up, choose a password with the emailed link, sign in (ADR-0033), on real PostgreSQL through the real filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class AccountsIntegrationTest {

    private static final String PASSWORD = "a long enough passphrase";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PiiCipher cipher;

    private final JsonMapper json = JsonMapper.builder().build();

    private MockHttpServletRequestBuilder body(String path, String content) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private void requestLink(String email) throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
    }

    /** The token from the newest link event for this address, read the way the notification service would. */
    private String latestToken(String email) {
        List<String> payloads = jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = 'account.password-link' ORDER BY seq DESC LIMIT 50", String.class);
        for (String payload : payloads) {
            var event = json.readTree(cipher.decrypt(payload));
            if (email.equalsIgnoreCase(event.get("contactEmail").asString())) {
                return event.get("token").asString();
            }
        }
        throw new AssertionError("No link was queued for " + email);
    }

    private long linkEvents(String email) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = 'account.password-link'", String.class).stream()
                .filter(p -> email.equalsIgnoreCase(json.readTree(cipher.decrypt(p)).get("contactEmail").asString())).count();
    }

    private String signIn(String email, String password) throws Exception {
        String response = mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asString();
    }

    @Test
    void should_sign_up_with_the_emailed_link_then_sign_in_and_see_the_account() throws Exception {
        requestLink("sign.up@example.com");
        String token = latestToken("sign.up@example.com");

        // Nobody can sign in before using the link, whatever password they try.
        mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"sign.up@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());

        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, PASSWORD)))
                .andExpect(status().isNoContent());

        String bearer = signIn("Sign.Up@Example.com", PASSWORD);   // the address is matched ignoring case
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("sign.up@example.com"));
    }

    @Test
    void should_store_the_address_encrypted_and_the_link_token_only_as_a_hash() throws Exception {
        requestLink("secret.person@example.com");
        String token = latestToken("secret.person@example.com");

        String stored = jdbc.queryForObject("SELECT email FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("secret.person@example.com"));
        String tokenHash = jdbc.queryForObject("SELECT link_token_hash FROM traveller_account WHERE email_index = ?", String.class,
                cipher.blindIndex("secret.person@example.com"));

        assertThat(stored).startsWith("v1:").doesNotContain("secret.person");
        assertThat(tokenHash).hasSize(64).isNotEqualTo(token);
    }

    @Test
    void should_work_only_once_for_the_link() throws Exception {
        requestLink("once@example.com");
        String token = latestToken("once@example.com");
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, PASSWORD))).andExpect(status().isNoContent());

        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"another long passphrase\"}".formatted(token)))
                .andExpect(status().isBadRequest());
        signIn("once@example.com", PASSWORD);   // the first password still stands
    }

    @Test
    void should_answer_a_link_request_the_same_for_an_address_with_an_account_and_not_resend_within_two_minutes() throws Exception {
        requestLink("repeat@example.com");
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(latestToken("repeat@example.com"), PASSWORD)))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE traveller_account SET link_issued_at = now() - interval '5 minutes' WHERE email_index = ?", cipher.blindIndex("repeat@example.com"));

        requestLink("repeat@example.com");   // a reset: allowed, and answered like any other request
        long afterReset = linkEvents("repeat@example.com");
        requestLink("repeat@example.com");   // straight away again: nothing more is sent

        assertThat(linkEvents("repeat@example.com")).isEqualTo(afterReset);
    }

    @Test
    void should_let_a_forgotten_password_be_replaced_through_a_new_link_and_stop_the_old_password_working() throws Exception {
        requestLink("forgot@example.com");
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(latestToken("forgot@example.com"), PASSWORD)))
                .andExpect(status().isNoContent());
        jdbc.update("UPDATE traveller_account SET link_issued_at = now() - interval '5 minutes' WHERE email_index = ?", cipher.blindIndex("forgot@example.com"));

        requestLink("forgot@example.com");
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"a brand new passphrase\"}".formatted(latestToken("forgot@example.com"))))
                .andExpect(status().isNoContent());

        signIn("forgot@example.com", "a brand new passphrase");
        mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"forgot@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_lock_after_five_wrong_passwords() throws Exception {
        requestLink("locked@example.com");
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(latestToken("locked@example.com"), PASSWORD)))
                .andExpect(status().isNoContent());

        for (int i = 0; i < 5; i++) {
            mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"locked@example.com\",\"password\":\"wrong wrong wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"locked@example.com\",\"password\":\"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());   // even the right one, while locked
    }

    @Test
    void should_refuse_a_link_request_that_is_not_an_address() throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"not an address\"}")).andExpect(status().isBadRequest());
    }
}
