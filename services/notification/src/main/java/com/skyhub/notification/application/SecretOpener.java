package com.skyhub.notification.application;

/**
 * Port: opens a secret sealed in an event: the one-time token of a link event, so it can be put in the emailed link and nowhere else
 * (ADR-0046), or a boarding pass, so it can be drawn and attached (ADR-0074). The application owns the interface; the cipher is an
 * adapter in integration.
 */
public interface SecretOpener {

    /**
     * @param eventType the type of the event the secret came in, and {@code address} the one it is for: it opens only for the pair it
     *                  was sealed for
     * @return the plain secret. Never log it, record it, or put it anywhere but where it is for (the link, or the picture).
     * @throws com.skyhub.notification.domain.InvalidEventException if it does not open (the wrong key, a changed value, another
     *                  address or event type, or no key configured); the message never holds the secret or the address
     */
    String open(String sealed, String eventType, String address);
}
