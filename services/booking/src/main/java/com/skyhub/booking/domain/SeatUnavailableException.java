package com.skyhub.booking.domain;

public class SeatUnavailableException extends RuntimeException {

    public SeatUnavailableException(String message) {
        super(message);
    }
}
