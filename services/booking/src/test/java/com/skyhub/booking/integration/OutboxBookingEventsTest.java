package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OutboxBookingEventsTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11");

    @Mock
    private OutboxEventRepository outbox;

    private final PiiCipher cipher = new PiiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final JsonMapper json = JsonMapper.builder().build();

    private OutboxBookingEvents events() {
        return new OutboxBookingEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC), () -> EVENT_ID);
    }

    private static Pnr pnr() {
        return Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", LocalDate.of(2026, 11, 2))),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D"))), NOW, "keyhash", "fingerprint");
    }

    private OutboxEvent recorded() {
        events().bookingConfirmed(pnr(), Money.of("100.00", "USD"));
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void should_write_the_event_the_notification_service_expects() throws Exception {
        OutboxEvent event = recorded();

        JsonNode expected = json.readTree(Files.readString(EXAMPLE));
        JsonNode actual = json.readTree(cipher.decrypt(event.getPayload()));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void should_key_the_event_by_booking_so_one_bookings_events_stay_in_order() {
        OutboxEvent event = recorded();

        assertThat(event.getId()).isEqualTo(EVENT_ID);
        assertThat(event.getAggregateId()).isEqualTo("ABC234");
        assertThat(event.getEventType()).isEqualTo("booking.confirmed");
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
        assertThat(event.isPublished()).isFalse();
    }

    @Test
    void should_never_keep_the_travellers_email_or_name_in_plain_text() {
        String stored = recorded().getPayload();

        assertThat(stored).doesNotContain("priya@example.com").doesNotContain("Perera").doesNotContain("ABC234");
    }
}
