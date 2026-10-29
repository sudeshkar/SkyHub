package com.skyhub.booking.domain;

public class PnrNotFoundException extends RuntimeException {

    public PnrNotFoundException(String message) {
        super(message);
    }
}
