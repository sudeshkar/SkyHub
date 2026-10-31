package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.skyhub.booking.TestLinkTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class AesGcmLinkTokenSealerTest {

    private static final String TOKEN = "dGVzdC10b2tlbi1ub3QtYS1yZWFsLW9uZS1hYWFhYWFhYQ";
    private final AesGcmLinkTokenSealer sealer = new AesGcmLinkTokenSealer(TestLinkTokens.TEST_KEY);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_seal_to_the_versioned_format_without_the_token_showing() {
        String sealed = sealer.seal(TOKEN, "account.password-link", "priya@example.com");

        assertThat(sealed).startsWith("v1:").doesNotContain(TOKEN);
        assertThat(Base64.getDecoder().decode(sealed.substring(3)).length).isEqualTo(12 + TOKEN.length() + 16);   // IV, ciphertext, tag
    }

    @Test
    void should_open_for_the_event_type_and_address_it_was_made_for() {
        String sealed = sealer.seal(TOKEN, "account.password-link", "priya@example.com");

        assertThat(TestLinkTokens.open(sealed, "account.password-link", "priya@example.com")).isEqualTo(TOKEN);
    }

    @Test
    void should_not_open_for_another_address_or_another_kind_of_event() {
        String sealed = sealer.seal(TOKEN, "account.password-link", "priya@example.com");

        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> TestLinkTokens.open(sealed, "account.password-link", "someone.else@example.com"));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> TestLinkTokens.open(sealed, "account.email-change-link", "priya@example.com"));
    }

    @Test
    void should_give_a_different_result_each_time_so_two_equal_tokens_are_not_recognisable() {
        assertThat(sealer.seal(TOKEN, "account.password-link", "priya@example.com"))
                .isNotEqualTo(sealer.seal(TOKEN, "account.password-link", "priya@example.com"));
    }

    @Test
    void should_agree_with_the_notification_services_shared_examples_in_both_directions() throws Exception {
        // The examples were sealed with the test key, bound to their own type and address. Opening them here, with code written
        // apart from the sealer, is what keeps the format the two services use from drifting.
        for (String file : new String[] {"account-password-link", "account-email-change-link"}) {
            var event = json.readTree(Files.readString(Path.of("../../api-specs/events/" + file + ".v1.example.json")));

            String token = TestLinkTokens.tokenOf(event);

            assertThat(token).isNotBlank();
            assertThat(event.has("token")).as("the plain token is never in the event").isFalse();
            // and what the sealer makes for the same type and address opens to the same kind of value
            String made = sealer.seal(token, event.get("type").asString(), event.get("contactEmail").asString());
            assertThat(TestLinkTokens.open(made, event.get("type").asString(), event.get("contactEmail").asString())).isEqualTo(token);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64 !!", "AAAA", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="})
    void should_refuse_to_start_without_a_proper_256_bit_key_and_say_which_setting(String badKey) {
        assertThatIllegalStateException().isThrownBy(() -> new AesGcmLinkTokenSealer(badKey)).withMessageContaining("SKYHUB_LINK_TOKEN_KEY");
    }
}
