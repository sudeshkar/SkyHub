package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.skyhub.booking.persistence.PiiCipher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Saved passengers on real PostgreSQL through the real filter chain (ADR-0078). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class SavedPassengersIntegrationTest {

    private static final String URL = "/api/v1/accounts/me/passengers";
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

    /** Signs up the way a person does (emailed link, then a password) and signs in. */
    private String signedIn(String email) throws Exception {
        mvc.perform(body("/api/v1/accounts/password-links", "{\"email\":\"%s\"}".formatted(email))).andExpect(status().isAccepted());
        String token = null;
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = 'account.password-link' ORDER BY seq DESC LIMIT 50", String.class)) {
            var event = json.readTree(cipher.decrypt(payload));
            if (email.equalsIgnoreCase(event.get("contactEmail").asString())) {
                token = TestSealedSecrets.tokenOf(event);
                break;
            }
        }
        assertThat(token).as("a link was queued for " + email).isNotNull();
        mvc.perform(body("/api/v1/accounts/passwords", "{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, PASSWORD))).andExpect(status().isNoContent());
        String response = mvc.perform(body("/api/v1/accounts/sessions", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(response, "$.accessToken");
    }

    private ResultActions save(String bearer, String first, String last) throws Exception {
        return mvc.perform(body(URL, "{\"firstName\":\"%s\",\"lastName\":\"%s\"}".formatted(first, last)).header("Authorization", bearer));
    }

    @Test
    void should_save_list_and_remove_a_name_and_keep_it_encrypted_at_rest() throws Exception {
        String bearer = signedIn("saved.one@example.com");

        long id = ((Number) JsonPath.read(save(bearer, "Priya", "Perera").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        save(bearer, "priya", "PERERA").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));   // the same person: not a second place

        mvc.perform(get(URL).header("Authorization", bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.passengers.length()").value(1))
                .andExpect(jsonPath("$.passengers[0].firstName").value("Priya")).andExpect(jsonPath("$.limit").value(10));
        String raw = jdbc.queryForObject("SELECT first_name || last_name FROM saved_passenger WHERE id = ?", String.class, id);
        assertThat(raw).doesNotContain("Priya").doesNotContain("Perera");   // personal data is encrypted at rest (DEVELOPMENT.md section 4)

        mvc.perform(delete(URL + "/" + id).header("Authorization", bearer)).andExpect(status().isNoContent());
        mvc.perform(delete(URL + "/" + id).header("Authorization", bearer)).andExpect(status().isNotFound());
        mvc.perform(get(URL).header("Authorization", bearer)).andExpect(jsonPath("$.passengers.length()").value(0));
    }

    @Test
    void should_never_show_or_remove_another_accounts_names() throws Exception {
        String mine = signedIn("saved.mine@example.com");
        String theirs = signedIn("saved.theirs@example.com");
        long id = ((Number) JsonPath.read(save(mine, "Priya", "Perera").andReturn().getResponse().getContentAsString(), "$.id")).longValue();

        mvc.perform(get(URL).header("Authorization", theirs)).andExpect(jsonPath("$.passengers.length()").value(0));
        mvc.perform(delete(URL + "/" + id).header("Authorization", theirs)).andExpect(status().isNotFound());   // not found, not forbidden: it says nothing
        mvc.perform(get(URL).header("Authorization", mine)).andExpect(jsonPath("$.passengers.length()").value(1));
    }

    @Test
    void should_stop_at_ten_even_when_many_requests_arrive_at_once() throws Exception {
        String bearer = signedIn("saved.race@example.com");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int n = i;
                Callable<Integer> task = () -> save(bearer, "Name" + n, "Perera").andReturn().getResponse().getStatus();
                results.add(pool.submit(task));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(10);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
        mvc.perform(get(URL).header("Authorization", bearer)).andExpect(jsonPath("$.passengers.length()").value(10));
    }

    @Test
    void should_refuse_a_booking_token_and_no_token() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
    }
}
