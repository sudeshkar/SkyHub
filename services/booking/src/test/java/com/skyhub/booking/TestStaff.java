package com.skyhub.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Logs in the way staff do, for integration tests. The administrator exists because the test properties set the
 * bootstrap account (skyhub.bootstrap.admin-*), so tests need no database setup of their own.
 */
public final class TestStaff {

    public static final String ADMIN_USERNAME = "test.admin";
    public static final String ADMIN_PASSWORD = "test-admin-passphrase";

    private TestStaff() {
    }

    /** An {@code Authorization} header value for the bootstrap administrator. */
    public static String adminBearer(MockMvc mvc) throws Exception {
        return login(mvc, ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    public static String login(MockMvc mvc, String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/ops/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(body, "$.accessToken");
    }
}
