package com.skyhub.booking.application;

/**
 * Port: seals a secret so that it can sit on the Kafka topic without being readable there: a one-time link token (ADR-0046) or a signed
 * boarding pass (ADR-0074). Only the notification service, which holds the same key, can open it, and only for the same event type and
 * address: a sealed secret copied into an event for another address does not open. The application owns the interface; the cipher is an
 * adapter in integration. Named for what it does, not for its first use (ADR-0075).
 */
public interface SecretSealer {

    /** @param eventType the type of the event the secret goes in, and {@code address} the one it is for: both are bound into the seal */
    String seal(String secret, String eventType, String address);
}
