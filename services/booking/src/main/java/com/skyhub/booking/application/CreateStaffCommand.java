package com.skyhub.booking.application;

import com.skyhub.booking.domain.StaffRole;

/** The password is plain text only until {@link StaffService} hashes it; it is kept out of {@code toString()}. */
public record CreateStaffCommand(String username, String password, StaffRole role) {

    @Override
    public String toString() {
        return "CreateStaffCommand[username=" + username + ", role=" + role + "]";
    }
}
