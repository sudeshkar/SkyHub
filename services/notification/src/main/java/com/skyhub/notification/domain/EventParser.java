package com.skyhub.notification.domain;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a message into an event, refusing anything that cannot be acted on. Unknown fields are ignored on purpose:
 * the producer may add fields without breaking us (ADR-0019).
 */
public final class EventParser {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventParser() {
    }

    /** The {@code type} of a message, so unknown kinds can be skipped without reading the rest. */
    public static String typeOf(String message) {
        JsonNode type = tree(message).get("type");
        if (type == null || !type.isString()) {
            throw new InvalidEventException("The message has no event type");
        }
        return type.asString();
    }

    public static BookingConfirmedEvent parse(String message) {
        BookingConfirmedEvent event;
        try {
            event = JSON.readValue(message, BookingConfirmedEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("The message is not a booking.confirmed event", e);
        }
        require(event != null, "empty message");
        require(event.eventId() != null, "no eventId");
        require(BookingConfirmedEvent.TYPE.equals(event.type()), "wrong type");
        require(notBlank(event.locator()), "no locator");
        require(notBlank(event.contactEmail()), "no contactEmail");
        require(event.passengers() != null && event.segments() != null, "no passengers or segments");
        require(event.totalAmount() != null && notBlank(event.currency()), "no total");
        return new BookingConfirmedEvent(event.eventId(), event.type(), event.version(), event.occurredAt(),
                event.locator(), event.contactEmail(), List.copyOf(event.passengers()), List.copyOf(event.segments()),
                event.totalAmount(), event.currency());
    }

    private static JsonNode tree(String message) {
        try {
            JsonNode node = JSON.readTree(message);
            if (!node.isObject()) {
                throw new InvalidEventException("The message is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new InvalidEventException("The message is not JSON", e);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean ok, String problem) {
        if (!ok) {
            throw new InvalidEventException("Invalid booking.confirmed event: " + problem);
        }
    }
}
