package com.skyhub.notification.application;

/**
 * Port: draws a boarding pass as a picture a phone can open with no signal and a gate can scan (ADR-0074). The application owns the
 * interface; the drawing library is an adapter in integration.
 */
public interface PassImages {

    /**
     * @param text the signed pass, as the gate reads it
     * @return a PNG of the text as a QR code, with the quiet zone round it
     * @throws IllegalArgumentException if the text is missing or empty
     */
    byte[] qrPng(String text);
}
