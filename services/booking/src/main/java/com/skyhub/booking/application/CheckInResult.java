package com.skyhub.booking.application;

import java.util.List;

/** {@code created} is false when the flight was already checked in and these are the existing passes. */
public record CheckInResult(List<BoardingPass> passes, boolean created) {
}
