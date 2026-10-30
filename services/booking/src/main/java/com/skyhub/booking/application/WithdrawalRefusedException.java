package com.skyhub.booking.application;

/** A disruption cannot be taken back because bookings were moved off the flight on the strength of it (ADR-0026). */
public class WithdrawalRefusedException extends RuntimeException {

    public WithdrawalRefusedException(String message) {
        super(message);
    }
}
