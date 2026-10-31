package com.skyhub.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.skyhub.notification.domain.InvalidEventException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opening the one-time token of a link event (ADR-0046). The shared examples were sealed by the booking side's format with a
 * test-only key (32 bytes of 0x02) and bound to their own event type and address.
 */
class AesGcmLinkTokenOpenerTest {

    private static final String TEST_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";
    private final AesGcmLinkTokenOpener opener = new AesGcmLinkTokenOpener(TEST_KEY);
    private final JsonMapper json = JsonMapper.builder().build();

    private tools.jackson.databind.JsonNode example(String file) throws Exception {
        return json.readTree(Files.readString(Path.of("../../api-specs/events/" + file + ".v1.example.json")));
    }

    @Test
    void should_open_the_password_link_example_for_its_own_type_and_address() throws Exception {
        var event = example("account-password-link");

        String token = opener.open(event.get("sealedToken").asString(), event.get("type").asString(), event.get("contactEmail").asString());

        assertThat(token).isEqualTo("dGVzdC10b2tlbi1ub3QtYS1yZWFsLW9uZS1hYWFhYWFhYQ");
    }

    @Test
    void should_open_the_email_change_example_for_its_own_type_and_address() throws Exception {
        var event = example("account-email-change-link");

        String token = opener.open(event.get("sealedToken").asString(), event.get("type").asString(), event.get("contactEmail").asString());

        assertThat(token).isEqualTo("bm90LWEtcmVhbC1vbmUtdGVzdC10b2tlbi1mb3ItZW1haWwtY2hhbmdl");
    }

    @Test
    void should_not_open_for_another_address_or_another_kind_of_event_so_a_copied_token_is_useless() throws Exception {
        var event = example("account-password-link");
        String sealed = event.get("sealedToken").asString();

        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> opener.open(sealed, "account.password-link", "someone.else@example.com"));
        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> opener.open(sealed, "account.email-change-link", "priya@example.com"));
        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> opener.open(sealed, "account.password-link", "PRIYA@example.com"));   // exactly the address it was made for
    }

    @Test
    void should_not_open_with_another_key() throws Exception {
        var event = example("account-password-link");
        AesGcmLinkTokenOpener other = new AesGcmLinkTokenOpener("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");

        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> other.open(event.get("sealedToken").asString(), "account.password-link", "priya@example.com"));
    }

    @Test
    void should_not_open_a_value_that_was_changed() throws Exception {
        String sealed = example("account-password-link").get("sealedToken").asString();
        char last = sealed.charAt(sealed.length() - 3);
        String changed = sealed.substring(0, sealed.length() - 3) + (last == 'A' ? 'B' : 'A') + sealed.substring(sealed.length() - 2);

        assertThatExceptionOfType(InvalidEventException.class).isThrownBy(() -> opener.open(changed, "account.password-link", "priya@example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "dGVzdC10b2tlbi1ub3QtYS1yZWFsLW9uZQ", "v1:", "v1:not base64 !!", "v1:AAAA", "v2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void should_refuse_anything_that_is_not_a_sealed_token(String notSealed) {
        assertThatExceptionOfType(InvalidEventException.class).isThrownBy(() -> opener.open(notSealed, "account.password-link", "priya@example.com"));
    }

    @Test
    void should_never_show_the_sealed_value_or_the_address_in_what_it_says_when_it_refuses() throws Exception {
        String sealed = example("account-password-link").get("sealedToken").asString();

        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> opener.open(sealed, "account.password-link", "someone.else@example.com"))
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(sealed).doesNotContain("someone.else").doesNotContain("priya"));
    }

    @Test
    void should_start_without_a_key_but_refuse_every_token_and_say_which_setting_is_missing() throws Exception {
        AesGcmLinkTokenOpener unconfigured = new AesGcmLinkTokenOpener("");   // booking emails do not need the key, so the service still starts

        assertThatExceptionOfType(InvalidEventException.class)
                .isThrownBy(() -> unconfigured.open(example("account-password-link").get("sealedToken").asString(), "account.password-link", "priya@example.com"))
                .withMessageContaining("SKYHUB_LINK_TOKEN_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not base64 !!", "AAAA", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="})
    void should_refuse_to_start_with_a_key_that_is_set_but_wrong_since_that_is_a_mistake_not_an_omission(String badKey) {
        assertThatIllegalStateException().isThrownBy(() -> new AesGcmLinkTokenOpener(badKey)).withMessageContaining("SKYHUB_LINK_TOKEN_KEY");
    }
}
