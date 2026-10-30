package com.skyhub.flightsearch.messaging;

import com.skyhub.flightsearch.domain.FlightDisruption;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Turns a message into an event, refusing anything that cannot be acted on. Unknown fields are ignored on purpose. */
final class FlightDisruptionParser {

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private FlightDisruptionParser() {
    }

    static String typeOf(String message) {
        try {
            JsonNode type = JSON.readTree(message).get("type");
            if (type == null || !type.isString()) {
                throw new InvalidFlightEventException("The message has no event type");
            }
            return type.asString();
        } catch (JacksonException e) {
            throw new InvalidFlightEventException("The message is not JSON", e);
        }
    }

    static FlightDisruption parse(String message) {
        FlightDisruption event;
        try {
            event = JSON.readValue(message, FlightDisruption.class);
        } catch (JacksonException e) {
            throw new InvalidFlightEventException("The message is not a flight.disrupted event", e);
        }
        require(event != null && event.eventId() != null, "no eventId");
        require(FlightDisruption.TYPE.equals(event.type()), "wrong type");
        require(event.occurredAt() != null, "no occurredAt");
        require(event.flightNumber() != null && !event.flightNumber().isBlank() && event.departureDate() != null, "no flight");
        require("DELAYED".equals(event.disruption()) || "CANCELLED".equals(event.disruption())
                || FlightDisruption.CLEARED.equals(event.disruption()), "unknown disruption");
        require(!"DELAYED".equals(event.disruption()) || event.revisedDeparture() != null, "a delay with no new time");
        return event;
    }

    private static void require(boolean ok, String problem) {
        if (!ok) {
            throw new InvalidFlightEventException("Invalid flight.disrupted event: " + problem);
        }
    }
}
