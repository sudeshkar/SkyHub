package com.skyhub.booking.domain;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
