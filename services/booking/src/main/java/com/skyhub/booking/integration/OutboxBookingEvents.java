package com.skyhub.booking.integration;

import com.skyhub.booking.application.BookingEvents;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter: records events in the outbox table (ADR-0019). It only inserts a row, in whatever transaction is
 * running, so it commits or rolls back with the booking. The payload carries the traveller's email and names, so it
 * is encrypted with the same key as the booking's own email before it is stored. The shape of the payload is the
 * contract in api-specs/events/booking-confirmed.v1.example.json.
 */
@Component
public class OutboxBookingEvents implements BookingEvents {

    static final String BOOKING_CONFIRMED = "booking.confirmed";

    private final OutboxEventRepository outbox;
    private final PiiCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public OutboxBookingEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock) {
        this(outbox, cipher, json, clock, UUID::randomUUID);
    }

    OutboxBookingEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock, Supplier<UUID> ids) {
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public void bookingConfirmed(Pnr pnr, Money total) {
        UUID id = ids.get();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id.toString());
        event.put("type", BOOKING_CONFIRMED);
        event.put("version", 1);
        event.put("occurredAt", clock.instant().toString());
        event.put("locator", pnr.getLocator());
        event.put("contactEmail", pnr.getContactEmail().value());
        event.put("passengers", pnr.getPassengers().stream()
                .map(p -> Map.of("firstName", p.getFirstName(), "lastName", p.getLastName())).toList());
        event.put("segments", List.copyOf(pnr.getSegments().stream()
                .map(s -> Map.of("flightNumber", s.flightNumber(), "departureDate", s.departureDate().toString())).toList()));
        event.put("totalAmount", total.amount());
        event.put("currency", total.currency());

        outbox.save(OutboxEvent.pending(id, pnr.getLocator(), BOOKING_CONFIRMED,
                cipher.encrypt(json.writeValueAsString(event)), clock.instant()));
    }
}
