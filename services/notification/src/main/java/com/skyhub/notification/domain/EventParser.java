package com.skyhub.notification.domain;

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

    public static <T extends NotificationEvent> T parse(String message, Class<T> kind) {
        T event;
        try {
            event = JSON.readValue(message, kind);
        } catch (JacksonException e) {
            throw new InvalidEventException("The message is not a valid " + kind.getSimpleName(), e);
        }
        require(event != null, "empty message");
        require(event.eventId() != null, "no eventId");
        require(notBlank(event.locator()), "no locator");
        require(notBlank(event.contactEmail()), "no contactEmail");
        switch (event) {
            case BookingConfirmedEvent e -> {
                require(BookingConfirmedEvent.TYPE.equals(e.type()), "wrong type");
                require(e.passengers() != null && e.segments() != null, "no passengers or segments");
                require(e.totalAmount() != null && notBlank(e.currency()), "no total");
            }
            case BookingCancelledEvent e -> {
                require(BookingCancelledEvent.TYPE.equals(e.type()), "wrong type");
                require(e.segments() != null, "no segments");
                if (e.refund() != null) {
                    String decision = e.refund().decision();
                    require("FULL_REFUND".equals(decision) || "NO_REFUND".equals(decision), "unknown refund decision");
                    require(e.refund().amount() != null && notBlank(e.refund().currency()), "no refund amount");
                }
            }
            case BookingRebookedEvent e -> {
                require(BookingRebookedEvent.TYPE.equals(e.type()), "wrong type");
                require(e.from() != null && e.to() != null, "no flights");
                require(e.passengers() != null && !e.passengers().isEmpty()
                        && e.passengers().stream().allMatch(p -> p.seat() != null && !p.seat().isBlank()), "no seats");
            }
            case BookingDisruptedEvent e -> {
                require(BookingDisruptedEvent.TYPE.equals(e.type()), "wrong type");
                require(notBlank(e.flightNumber()) && e.departureDate() != null, "no flight");
                require("DELAYED".equals(e.disruption()) || "CANCELLED".equals(e.disruption()), "unknown disruption");
                require(!"DELAYED".equals(e.disruption()) || e.revisedDeparture() != null, "a delay with no new time");
            }
        }
        return event;
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
            throw new InvalidEventException("Invalid event: " + problem);
        }
    }
}
