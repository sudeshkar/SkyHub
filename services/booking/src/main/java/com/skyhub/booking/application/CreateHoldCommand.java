package com.skyhub.booking.application;

import java.util.List;

public record CreateHoldCommand(List<HeldSeat> items) {
}
