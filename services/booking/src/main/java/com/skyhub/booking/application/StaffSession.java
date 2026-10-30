package com.skyhub.booking.application;

import com.skyhub.booking.domain.StaffRole;

public record StaffSession(StaffToken token, String username, StaffRole role) {
}
