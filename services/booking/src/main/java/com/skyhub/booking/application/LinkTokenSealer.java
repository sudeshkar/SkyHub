package com.skyhub.booking.application;

/**
 * Port: seals a one-time link token so that it can sit on the Kafka topic without being readable there (ADR-0046). Only the
 * notification service, which holds the same key, can open it, and only for the same event type and address: a sealed token copied
 * into an event for another address does not open. The application owns the interface; the cipher is an adapter in integration.
 */
public interface LinkTokenSealer {

    /** @param eventType the type of the event the token goes in, and {@code address} the one the link is for: both are bound into the seal */
    String seal(String token, String eventType, String address);
}
