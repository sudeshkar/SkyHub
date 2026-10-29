package com.skyhub.booking.application;

/**
 * Port: makes a boarding pass payload tamper-evident for the gate scanner. The application owns the
 * interface so the signature scheme (shared-key HMAC today, asymmetric later) can change without touching check-in.
 */
public interface BoardingPassSigner {

    /** @return the payload followed by its signature */
    String sign(String payload);

    /** @return true only for text this signer produced and nobody has changed */
    boolean verify(String signed);
}
