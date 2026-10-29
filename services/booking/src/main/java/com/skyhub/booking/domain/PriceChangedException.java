package com.skyhub.booking.domain;

/** The price the traveller was shown is no longer the price. Nothing was charged. */
public class PriceChangedException extends RuntimeException {

    public PriceChangedException(String message) {
        super(message);
    }
}
