package com.skyhub.booking.application;

import java.time.Instant;
import java.util.List;

public record HoldView(String holdId, Instant expiresAt, List<HeldSeat> items) {
}
