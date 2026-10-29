package com.skyhub.booking.domain;

public class BookingCancelledException extends RuntimeException {

    public BookingCancelledException(String message) {
        super(message);
    }
}
