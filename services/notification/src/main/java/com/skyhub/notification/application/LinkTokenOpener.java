package com.skyhub.notification.application;

/**
 * Port: opens the sealed one-time token of a link event, so it can be put in the emailed link and nowhere else (ADR-0046). The
 * application owns the interface; the cipher is an adapter in integration.
 */
public interface LinkTokenOpener {

    /**
     * @param eventType the type of the event the token came in, and {@code address} the one it is for: the token opens only for the
     *                  pair it was sealed for
     * @return the plain token. Never log it, record it, or put it anywhere but the link.
     * @throws com.skyhub.notification.domain.InvalidEventException if it does not open (the wrong key, a changed value, another
     *                  address or event type, or no key configured); the message never holds the token or the address
     */
    String open(String sealedToken, String eventType, String address);
}
