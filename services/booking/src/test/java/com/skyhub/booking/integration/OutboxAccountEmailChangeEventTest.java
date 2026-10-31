package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

class OutboxAccountEmailChangeEventTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final PiiCipher cipher = new PiiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void should_write_the_email_change_event_as_the_notification_service_expects() throws Exception {
        new OutboxAccountEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString("6c1e9a52-7d3b-4f08-b4a6-2e9f0d8c3a14"))
                .emailChangeRequested(7L, new ContactEmail("priya.new@example.com"),
                        "bm90LWEtcmVhbC1vbmUtdGVzdC10b2tlbi1mb3ItZW1haWwtY2hhbmdl", Instant.parse("2026-11-01T11:00:00Z"));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("account.email-change-link");
        assertThat(saved.getValue().getAggregateId()).isEqualTo("account-7");
        assertThat(json.readTree(cipher.decrypt(saved.getValue().getPayload())))
                .isEqualTo(json.readTree(Files.readString(Path.of("../../api-specs/events/account-email-change-link.v1.example.json"))));
    }

    @Test
    void should_keep_the_token_and_the_new_address_out_of_the_stored_payload_in_the_clear() {
        new OutboxAccountEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC))
                .emailChangeRequested(7L, new ContactEmail("priya.new@example.com"), "secret-token", NOW);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getPayload()).doesNotContain("secret-token").doesNotContain("priya.new@example.com");
    }
}
