package com.skyhub.booking.application;

import com.skyhub.booking.domain.StaffRole;

public record StaffAccountView(String username, StaffRole role) {
}
