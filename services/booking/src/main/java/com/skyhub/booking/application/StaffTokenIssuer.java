package com.skyhub.booking.application;

import com.skyhub.booking.domain.StaffRole;

/** Port: issues the credential that carries a staff member's name and role. The JWT adapter lives in security. */
@FunctionalInterface
public interface StaffTokenIssuer {

    StaffToken issue(String username, StaffRole role);
}
