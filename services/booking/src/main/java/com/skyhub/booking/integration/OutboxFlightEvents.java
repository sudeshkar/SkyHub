package com.skyhub.booking.integration;

import com.skyhub.booking.application.FlightEvents;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter: records "this flight is now delayed / cancelled" in the outbox, for the <em>compacted</em> topic
 * {@code skyhub.flight.disruptions}, keyed by flight and date (ADR-0025). Compaction keeps only the latest event per key,
 * forever, so a service that starts later replays the topic and learns the current state of every disrupted flight. The
 * shape of the payload is api-specs/events/flight-disrupted.v1.example.json.
 */
@Component
public class OutboxFlightEvents implements FlightEvents {

    static final String TOPIC = "skyhub.flight.disruptions";
    static final String FLIGHT_DISRUPTED = "flight.disrupted";

    private final OutboxEventRepository outbox;
    private final PiiCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public OutboxFlightEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock) {
        this(outbox, cipher, json, clock, UUID::randomUUID);
    }

    OutboxFlightEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock, Supplier<UUID> ids) {
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public void flightDisrupted(Disruption disruption) {
        UUID id = ids.get();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id.toString());
        event.put("type", FLIGHT_DISRUPTED);
        event.put("version", 1);
        event.put("occurredAt", clock.instant().toString());
        event.put("flightNumber", disruption.getFlightNumber());
        event.put("departureDate", disruption.getDepartureDate().toString());
        event.put("disruption", disruption.getType().name());
        if (disruption.getRevisedDeparture() != null) {
            event.put("revisedDeparture", disruption.getRevisedDeparture().toString());
        }
        event.put("reason", disruption.getReason());
        String key = disruption.getFlightNumber() + "@" + disruption.getDepartureDate();
        outbox.save(OutboxEvent.pending(id, key, FLIGHT_DISRUPTED, cipher.encrypt(json.writeValueAsString(event)),
                clock.instant(), TOPIC));
    }
}
