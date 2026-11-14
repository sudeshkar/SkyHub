package com.skyhub.booking.integration;

import com.skyhub.booking.application.BookingEvents;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.RefundPolicy;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter: records events in the outbox table (ADR-0019). It only inserts a row, in whatever transaction is
 * running, so it commits or rolls back with the change it describes. Payloads carry the traveller's email, so they
 * are encrypted with the same key as the booking's own email before they are stored. The shape of each payload is
 * the contract in api-specs/events/*.v1.example.json, which tests in both services read.
 */
@Component
public class OutboxBookingEvents implements BookingEvents {

    static final String BOOKING_CONFIRMED = "booking.confirmed";
    static final String BOOKING_CANCELLED = "booking.cancelled";
    static final String BOOKING_DISRUPTED = "booking.disrupted";
    static final String BOOKING_DISRUPTION_WITHDRAWN = "booking.disruption-withdrawn";
    static final String BOOKING_CHECK_IN_OPEN = "booking.check-in-open";
    static final String BOOKING_REFUND_COMPLETED = "booking.refund-completed";
    static final String BOOKING_REBOOKED = "booking.rebooked";
    static final String BOOKING_CHECKED_IN = "booking.checked-in";

    private final OutboxEventRepository outbox;
    private final PiiCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final com.skyhub.booking.application.SecretSealer sealer;
    private final Supplier<UUID> ids;

    @Autowired
    public OutboxBookingEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock,
                               com.skyhub.booking.application.SecretSealer sealer) {
        this(outbox, cipher, json, clock, sealer, UUID::randomUUID);
    }

    OutboxBookingEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock,
                        com.skyhub.booking.application.SecretSealer sealer, Supplier<UUID> ids) {
        this.sealer = sealer;
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public void bookingConfirmed(Pnr pnr, Money total, List<com.skyhub.booking.application.FlightInfo> flights) {
        Map<String, Object> event = event(BOOKING_CONFIRMED, pnr);
        // Each passenger's seat on each flight, in segment order, and each flight's route and times as it was priced (ADR-0087).
        event.put("passengers", pnr.getPassengers().stream()
                .map(p -> Map.of("firstName", p.getFirstName(), "lastName", p.getLastName(), "seats", p.getSeats())).toList());
        List<PnrSegment> segments = pnr.getSegments();
        List<Map<String, Object>> described = new java.util.ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            described.add(itineraryLeg(segments.get(i), i < flights.size() ? flights.get(i) : null));
        }
        event.put("segments", described);
        event.put("totalAmount", total.amount());
        event.put("currency", total.currency());
        enqueue(pnr, event);
    }

    @Override
    public void bookingCancelled(Pnr pnr, RefundPolicy.Decision decision, Optional<Money> paid) {
        Map<String, Object> event = event(BOOKING_CANCELLED, pnr);
        event.put("segments", segments(pnr));
        paid.ifPresent(money -> {
            Map<String, Object> refund = new LinkedHashMap<>();
            refund.put("decision", decision.name());
            refund.put("amount", money.amount());
            refund.put("currency", money.currency());
            event.put("refund", refund);
        });
        enqueue(pnr, event);
    }

    @Override
    public void bookingDisrupted(Pnr pnr, Disruption disruption) {
        Map<String, Object> event = event(BOOKING_DISRUPTED, pnr);
        event.put("flightNumber", disruption.getFlightNumber());
        event.put("departureDate", disruption.getDepartureDate().toString());
        event.put("disruption", disruption.getType().name());
        if (disruption.getRevisedDeparture() != null) {
            event.put("revisedDeparture", disruption.getRevisedDeparture().toString());
        }
        event.put("reason", disruption.getReason());
        enqueue(pnr, event);
    }

    @Override
    public void bookingDisruptionWithdrawn(Pnr pnr, Disruption withdrawn) {
        Map<String, Object> event = event(BOOKING_DISRUPTION_WITHDRAWN, pnr);
        event.put("flightNumber", withdrawn.getFlightNumber());
        event.put("departureDate", withdrawn.getDepartureDate().toString());
        event.put("disruption", withdrawn.getType().name());
        enqueue(pnr, event);
    }

    @Override
    public void refundCompleted(Pnr pnr, Money amount) {
        Map<String, Object> event = event(BOOKING_REFUND_COMPLETED, pnr);
        event.put("amount", amount.amount());
        event.put("currency", amount.currency());
        enqueue(pnr, event);
    }

    @Override
    public void checkInOpened(Pnr pnr, PnrSegment segment, java.time.Instant departure, java.time.Instant closes) {
        Map<String, Object> event = event(BOOKING_CHECK_IN_OPEN, pnr);
        event.put("flightNumber", segment.flightNumber());
        event.put("departureDate", segment.departureDate().toString());
        event.put("departure", departure.toString());
        event.put("checkInCloses", closes.toString());
        enqueue(pnr, event);
    }

    @Override
    public void checkedIn(Pnr pnr, PnrSegment segment, java.util.List<com.skyhub.booking.application.BoardingPass> passes,
                          java.time.Instant departure, java.time.Instant closes) {
        Map<String, Object> event = event(BOOKING_CHECKED_IN, pnr);
        event.put("flightNumber", segment.flightNumber());
        event.put("departureDate", segment.departureDate().toString());
        event.put("departure", departure.toString());
        event.put("checkInCloses", closes.toString());
        int leg = pnr.getSegments().indexOf(segment);
        String address = pnr.getContactEmail().value();
        event.put("passengers", passes.stream().map(pass -> {
            var passenger = pnr.getPassengers().get(pass.passengerIndex());
            // The signed pass is a credential for boarding: on the topic it is only ever sealed, for this type and address (ADR-0074).
            return Map.of("firstName", passenger.getFirstName(), "lastName", passenger.getLastName(),
                    "seat", passenger.getSeats().get(leg), "boardingNumber", pass.sequence(),
                    "sealedPass", sealer.seal(pass.barcode(), BOOKING_CHECKED_IN, address));
        }).toList());
        enqueue(pnr, event);
    }

    @Override
    public void bookingRebooked(Pnr pnr, PnrSegment from, PnrSegment to, boolean checkedOut,
                                com.skyhub.booking.application.FlightInfo fromInfo, com.skyhub.booking.application.FlightInfo toInfo) {
        Map<String, Object> event = event(BOOKING_REBOOKED, pnr);
        // Each flight with its route and times, the same shape as a confirmed booking's segments (ADR-0087, ADR-0088).
        event.put("from", itineraryLeg(from, fromInfo));
        event.put("to", itineraryLeg(to, toInfo));
        int leg = pnr.getSegments().indexOf(to);
        event.put("passengers", pnr.getPassengers().stream().map(p -> Map.of("firstName", p.getFirstName(),
                "lastName", p.getLastName(), "seat", p.getSeats().get(leg))).toList());
        event.put("checkedOut", checkedOut);
        enqueue(pnr, event);
    }

    /** The fields every event has. */
    private Map<String, Object> event(String type, Pnr pnr) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", ids.get().toString());
        event.put("type", type);
        event.put("version", 1);
        event.put("occurredAt", clock.instant().toString());
        event.put("locator", pnr.getLocator());
        event.put("contactEmail", pnr.getContactEmail().value());
        return event;
    }

    /** A flight with what the catalog knew about it; a field it did not know is left out, never guessed. */
    private static Map<String, Object> itineraryLeg(PnrSegment segment, com.skyhub.booking.application.FlightInfo flight) {
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("flightNumber", segment.flightNumber());
        leg.put("departureDate", segment.departureDate().toString());
        if (flight != null) {
            putIfKnown(leg, "origin", flight.origin());
            putIfKnown(leg, "destination", flight.destination());
            putIfKnown(leg, "departure", flight.departure());
            putIfKnown(leg, "arrival", flight.arrival());
        }
        return leg;
    }

    private static void putIfKnown(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value.toString());
        }
    }

    private static List<Map<String, String>> segments(Pnr pnr) {
        return pnr.getSegments().stream()
                .map(s -> Map.of("flightNumber", s.flightNumber(), "departureDate", s.departureDate().toString())).toList();
    }

    private void enqueue(Pnr pnr, Map<String, Object> event) {
        outbox.save(OutboxEvent.pending(UUID.fromString((String) event.get("eventId")), pnr.getLocator(),
                (String) event.get("type"), cipher.encrypt(json.writeValueAsString(event)), clock.instant()));
    }
}
