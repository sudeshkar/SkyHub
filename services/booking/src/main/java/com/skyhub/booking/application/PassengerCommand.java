package com.skyhub.booking.application;

import java.util.List;

/** {@code seats} holds one seat number per segment, in segment order. */
public record PassengerCommand(String firstName, String lastName, List<String> seats) {
}
