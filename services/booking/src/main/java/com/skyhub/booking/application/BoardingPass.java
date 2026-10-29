package com.skyhub.booking.application;

import java.time.LocalDate;

/** What is printed on a boarding pass, plus the signed barcode a gate scans. Contains no more than the pass shows. */
public record BoardingPass(String passengerName, String locator, String flightNumber, LocalDate departureDate,
                           String seatNumber, int sequence, String barcode) {
}
