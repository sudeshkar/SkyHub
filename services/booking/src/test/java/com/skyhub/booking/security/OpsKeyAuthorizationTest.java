package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class OpsKeyAuthorizationTest {

    private static final String KEY = "an-operations-key-of-at-least-32-characters";
    private final OpsKeyAuthorization authorization = new OpsKeyAuthorization(KEY);

    private static boolean granted(OpsKeyAuthorization authorization, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ops/disruptions");
        if (header != null) {
            request.addHeader("X-Ops-Key", header);
        }
        return authorization.authorize(() -> (Authentication) null, new RequestAuthorizationContext(request)).isGranted();
    }

    @Test
    void should_grant_the_exact_key() {
        assertThat(granted(authorization, KEY)).isTrue();
    }

    @Test
    void should_refuse_a_missing_wrong_or_nearly_right_key() {
        assertThat(granted(authorization, null)).isFalse();
        assertThat(granted(authorization, "")).isFalse();
        assertThat(granted(authorization, "wrong")).isFalse();
        assertThat(granted(authorization, KEY + " ")).isFalse();
        assertThat(granted(authorization, KEY.substring(1))).isFalse();
    }

    @Test
    void should_refuse_everything_when_no_key_is_configured() {
        OpsKeyAuthorization disabled = new OpsKeyAuthorization("");

        assertThat(granted(disabled, "")).isFalse();
        assertThat(granted(disabled, KEY)).isFalse();
        assertThat(granted(disabled, null)).isFalse();
    }

    @Test
    void should_refuse_to_start_with_a_weak_key() {
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> new OpsKeyAuthorization("short"))
                .withMessageContaining("SKYHUB_OPS_KEY");
    }
}
