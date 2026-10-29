package com.skyhub.booking.application;

/** The payment provider could not be reached or did not answer. Nothing is known to have been charged. */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
