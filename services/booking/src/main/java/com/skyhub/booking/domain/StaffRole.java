package com.skyhub.booking.domain;

/** What a staff member may do (ADR-0017). Everything not listed here is denied. */
public enum StaffRole {
    /** Operations staff: record delays and cancellations. */
    OPS(false),
    /** Administrators: everything operations can do, and create staff accounts. */
    ADMIN(true);

    private final boolean createsStaff;

    StaffRole(boolean createsStaff) {
        this.createsStaff = createsStaff;
    }

    public boolean mayRecordDisruptions() {
        return true; // both roles; written out so the rule is findable and the next role has to decide
    }

    public boolean mayCreateStaff() {
        return createsStaff;
    }
}
