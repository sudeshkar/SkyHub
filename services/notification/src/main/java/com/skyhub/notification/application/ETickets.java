package com.skyhub.notification.application;

/**
 * Port: draws the e-ticket, the itinerary receipt that goes with a booking confirmation (ADR-0087). The application owns the
 * interface; the PDF library is an adapter in integration, like the boarding-pass pictures (ADR-0074).
 */
public interface ETickets {

    /**
     * @return a one-page PDF of the itinerary
     * @throws RuntimeException if it cannot be drawn; the confirmation is then sent without it
     */
    byte[] pdf(Itinerary itinerary);
}
