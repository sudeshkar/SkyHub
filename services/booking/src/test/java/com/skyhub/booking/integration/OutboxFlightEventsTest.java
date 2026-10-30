package com.skyhub.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OutboxFlightEventsTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/flight-disrupted.v1.example.json");
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final UUID ID = UUID.fromString("4f8a2b6c-91d3-4e7a-8c52-3b1d9e6f7a10");

    @Mock
    private OutboxEventRepository outbox;

    private final PiiCipher cipher = new PiiCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final JsonMapper json = JsonMapper.builder().build();

    private OutboxEvent recorded(Disruption disruption) {
        new OutboxFlightEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC), () -> ID).flightDisrupted(disruption);
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void should_write_the_event_flight_search_expects() throws Exception {
        OutboxEvent event = recorded(Disruption.delayed("SK501", LocalDate.of(2026, 11, 2),
                Instant.parse("2026-11-02T14:30:00Z"), "Late inbound aircraft", NOW));

        assertThat(json.readTree(cipher.decrypt(event.getPayload()))).isEqualTo(json.readTree(Files.readString(EXAMPLE)));
    }

    @Test
    void should_go_to_the_compacted_topic_keyed_by_flight_and_date_so_the_latest_state_of_each_flight_is_kept() {
        OutboxEvent event = recorded(Disruption.cancelled("SK501", LocalDate.of(2026, 11, 2), "Aircraft grounded", NOW));

        assertThat(event.getTopic()).isEqualTo("skyhub.flight.disruptions");
        assertThat(event.getAggregateId()).isEqualTo("SK501@2026-11-02");
        assertThat(event.getEventType()).isEqualTo("flight.disrupted");
    }

    @Test
    void should_publish_that_a_flight_is_back_to_normal_on_the_same_compacted_key() throws Exception {
        new OutboxFlightEvents(outbox, cipher, json, Clock.fixed(NOW, ZoneOffset.UTC), () -> ID)
                .flightCleared("SK501", LocalDate.of(2026, 11, 2));
        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());

        var payload = json.readTree(cipher.decrypt(saved.getValue().getPayload()));

        assertThat(saved.getValue().getAggregateId()).isEqualTo("SK501@2026-11-02");   // the key the disruption used: it replaces it
        assertThat(saved.getValue().getTopic()).isEqualTo("skyhub.flight.disruptions");
        assertThat(payload.get("disruption").asString()).isEqualTo("CLEARED");
        assertThat(payload.has("revisedDeparture")).isFalse();
    }

    @Test
    void should_leave_the_revised_time_out_for_a_cancelled_flight() throws Exception {
        OutboxEvent event = recorded(Disruption.cancelled("SK501", LocalDate.of(2026, 11, 2), "Aircraft grounded", NOW));

        var payload = json.readTree(cipher.decrypt(event.getPayload()));

        assertThat(payload.get("disruption").asString()).isEqualTo("CANCELLED");
        assertThat(payload.has("revisedDeparture")).isFalse();
    }
}
