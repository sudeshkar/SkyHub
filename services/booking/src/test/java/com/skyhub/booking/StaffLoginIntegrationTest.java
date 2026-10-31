package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * Staff login and roles (ADR-0017) on real PostgreSQL through the real filter chain. The bootstrap administrator
 * comes from the test properties; every test creates its own staff names so tests stay independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class StaffLoginIntegrationTest {

    private static final String PASSWORD = "a-long-enough-passphrase";
    private static final String DISRUPTION = "{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-06-01\","
            + "\"type\":\"CANCELLED\",\"reason\":\"Aircraft grounded\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String unique(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    private MockHttpServletRequestBuilder createStaff(String bearer, String username, String role) {
        MockHttpServletRequestBuilder request = post("/api/v1/ops/staff").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"%s\"}".formatted(username, PASSWORD, role));
        return bearer == null ? request : request.header("Authorization", bearer);
    }

    private MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/v1/ops/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    }

    private MockHttpServletRequestBuilder disrupt(String bearer) {
        MockHttpServletRequestBuilder request = post("/api/v1/ops/disruptions")
                .contentType(MediaType.APPLICATION_JSON).content(DISRUPTION);
        return bearer == null ? request : request.header("Authorization", bearer);
    }

    @Test
    void should_start_with_the_bootstrap_administrator_who_can_log_in() throws Exception {
        mvc.perform(login(TestStaff.ADMIN_USERNAME, TestStaff.ADMIN_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value(TestStaff.ADMIN_USERNAME))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void should_let_an_admin_create_ops_staff_who_can_then_record_a_disruption() throws Exception {
        String username = unique("sam");
        String admin = TestStaff.adminBearer(mvc);

        mvc.perform(createStaff(admin, username, "OPS"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("OPS"))
                .andExpect(jsonPath("$.password").doesNotExist());

        String ops = TestStaff.login(mvc, username, PASSWORD);
        mvc.perform(disrupt(ops)).andExpect(status().isOk());
    }

    @Test
    void should_keep_only_a_hash_of_the_password() throws Exception {
        String username = unique("hash");
        mvc.perform(createStaff(TestStaff.adminBearer(mvc), username, "OPS")).andExpect(status().isCreated());

        String stored = jdbc.queryForObject("SELECT password_hash FROM staff_account WHERE username = ?", String.class, username);

        assertThat(stored).isNotEqualTo(PASSWORD).startsWith("$2");
    }

    @Test
    void should_not_let_ops_staff_create_accounts() throws Exception {
        String username = unique("lee");
        mvc.perform(createStaff(TestStaff.adminBearer(mvc), username, "OPS")).andExpect(status().isCreated());
        String ops = TestStaff.login(mvc, username, PASSWORD);

        mvc.perform(createStaff(ops, unique("other"), "OPS")).andExpect(status().isForbidden());
    }

    @Test
    void should_refuse_everyone_who_is_not_logged_in_or_has_the_wrong_kind_of_token() throws Exception {
        mvc.perform(disrupt(null)).andExpect(status().isUnauthorized());
        mvc.perform(createStaff(null, unique("x"), "OPS")).andExpect(status().isUnauthorized());
        mvc.perform(disrupt("Bearer not-a-token")).andExpect(status().isUnauthorized());
        // the old shared key no longer opens anything
        mvc.perform(post("/api/v1/ops/disruptions").header("X-Ops-Key", "test-operations-key-test-operations-key")
                        .contentType(MediaType.APPLICATION_JSON).content(DISRUPTION))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_refuse_a_traveller_token_on_the_staff_endpoints() throws Exception {
        // A real booking token, for a booking that need not exist: the rule looks at scope and role, not the booking.
        String traveller = tokenForSomeBooking();

        mvc.perform(disrupt(traveller)).andExpect(status().isForbidden());
        mvc.perform(createStaff(traveller, unique("x"), "ADMIN")).andExpect(status().isForbidden());
    }

    @Test
    void should_answer_a_wrong_password_and_an_unknown_user_identically() throws Exception {
        String known = unique("kim");
        mvc.perform(createStaff(TestStaff.adminBearer(mvc), known, "OPS")).andExpect(status().isCreated());

        String wrong = mvc.perform(login(known, "wrong-password-here")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(login(unique("ghost"), PASSWORD)).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(wrong).isEqualTo(unknown).doesNotContain(known);
    }

    @Test
    void should_lock_the_account_after_five_wrong_passwords_even_for_the_right_one() throws Exception {
        String username = unique("lock");
        mvc.perform(createStaff(TestStaff.adminBearer(mvc), username, "OPS")).andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            mvc.perform(login(username, "wrong-password-" + i)).andExpect(status().isUnauthorized());
        }

        mvc.perform(login(username, PASSWORD)).andExpect(status().isUnauthorized());
        Boolean locked = jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM staff_account WHERE username = ?",
                Boolean.class, username);
        assertThat(locked).isTrue();
    }

    @Test
    void should_refuse_a_taken_username_and_a_weak_password_or_bad_name() throws Exception {
        String username = unique("dup");
        String admin = TestStaff.adminBearer(mvc);
        mvc.perform(createStaff(admin, username, "OPS")).andExpect(status().isCreated());

        mvc.perform(createStaff(admin, username, "OPS")).andExpect(status().isConflict());
        mvc.perform(createStaff(admin, username.toUpperCase(), "OPS")).andExpect(status().isBadRequest()); // pattern is lower-case
        mvc.perform(post("/api/v1/ops/staff").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"short\",\"role\":\"OPS\"}".formatted(unique("weak"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_record_who_recorded_each_disruption() throws Exception {
        String username = unique("audit");
        mvc.perform(createStaff(TestStaff.adminBearer(mvc), username, "OPS")).andExpect(status().isCreated());
        String ops = TestStaff.login(mvc, username, PASSWORD);

        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", ops).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"flightNumber\":\"SK502\",\"departureDate\":\"2027-06-02\",\"type\":\"CANCELLED\","
                                + "\"reason\":\"Crew shortage\"}"))
                .andExpect(status().isOk());

        String who = jdbc.queryForObject(
                "SELECT recorded_by FROM disruption WHERE flight_number = 'SK502' AND departure_date = '2027-06-02'", String.class);
        assertThat(who).isEqualTo(username);
    }

    private String tokenForSomeBooking() throws Exception {
        String locator = "ZZ" + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        // issue directly: no booking is needed to prove the staff rules reject a manage-scope token
        return "Bearer " + manageTokens.issue(locator, 0).value();
    }

    @Autowired
    private com.skyhub.booking.application.ManageTokenIssuer manageTokens;

    @Test
    void should_answer_400_not_500_for_a_password_the_hashing_cannot_store() throws Exception {
        String admin = TestStaff.adminBearer(mvc);

        mvc.perform(post("/api/v1/ops/staff").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"OPS\"}".formatted(unique("long"), "a".repeat(73))))
                .andExpect(status().isBadRequest());   // the contract's maximum catches ASCII
        mvc.perform(post("/api/v1/ops/staff").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"OPS\"}".formatted(unique("accents"), "é".repeat(50))))
                .andExpect(status().isBadRequest())   // 50 characters but 100 bytes: only the service can see that
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("72")));
        mvc.perform(post("/api/v1/ops/staff").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\",\"role\":\"OPS\"}".formatted(unique("longest"), "é".repeat(36))))
                .andExpect(status().isCreated());   // and exactly 72 bytes is stored and works
    }
}
