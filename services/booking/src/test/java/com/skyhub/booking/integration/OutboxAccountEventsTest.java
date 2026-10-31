package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skyhub.booking.TestLinkTokens;
import com.skyhub.booking.application.PasswordLinkPurpose;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OutboxAccountEventsTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PiiCipher cipher = new PiiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final JsonMapper json = JsonMapper.builder().build();
    private static final AesGcmLinkTokenSealer SEALER = new AesGcmLinkTokenSealer(TestLinkTokens.TEST_KEY);

    @Test
    void should_write_the_password_link_event_as_the_notification_service_expects() throws Exception {
        new OutboxAccountEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC), SEALER,
                () -> UUID.fromString("2b6d8f10-4c93-4a57-9e21-8f0a1c3d5e77"))
                .passwordLinkRequested(7L, new ContactEmail("priya@example.com"), "dGVzdC10b2tlbi1ub3QtYS1yZWFsLW9uZS1hYWFhYWFhYQ",
                        PasswordLinkPurpose.SIGN_UP, Instant.parse("2026-10-30T11:00:00Z"));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("account.password-link");
        assertThat(saved.getValue().getAggregateId()).isEqualTo("account-7");
        var made = (ObjectNode) json.readTree(cipher.decrypt(saved.getValue().getPayload()));
        var example = (ObjectNode) json.readTree(Files.readString(Path.of("../../api-specs/events/account-password-link.v1.example.json")));
        // The seal has a random IV, so it differs; what matters is that it opens, for this type and address, to the same token.
        assertThat(made.get("sealedToken").asString()).startsWith("v1:").isNotEqualTo(example.get("sealedToken").asString());
        assertThat(TestLinkTokens.tokenOf(made)).isEqualTo(TestLinkTokens.tokenOf(example));
        assertThat(made.has("token")).as("the plain token is never in the event").isFalse();
        made.remove("sealedToken");
        example.remove("sealedToken");
        assertThat(made).isEqualTo(example);   // everything else is exactly the shared example
    }

    @Test
    void should_keep_the_token_and_the_address_out_of_the_stored_payload_in_the_clear() {
        new OutboxAccountEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC), SEALER)
                .passwordLinkRequested(7L, new ContactEmail("priya@example.com"), "secret-token", PasswordLinkPurpose.RESET, NOW);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getPayload()).doesNotContain("secret-token").doesNotContain("priya@example.com");
        // Even decrypted, as the relay publishes it to the topic, the token is not readable: only sealed (ADR-0046).
        assertThat(cipher.decrypt(saved.getValue().getPayload())).doesNotContain("secret-token").contains("sealedToken");
    }
}
