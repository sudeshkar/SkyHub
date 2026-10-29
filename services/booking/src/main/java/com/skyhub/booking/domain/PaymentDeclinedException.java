package com.skyhub.booking.domain;

/** The provider refused the payment (declined card, unknown token). The message is safe to show to the traveller. */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
