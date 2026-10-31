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

    /** Who is booked on a flight (without names or contact details): both roles, so a disruption can be worked out. */
    public boolean mayViewAffectedBookings() {
        return true;
    }

    /** Move a booking off a disrupted flight. Both roles; every move is audited with the person's name. */
    public boolean mayRebook() {
        return true;
    }

    public boolean mayRecordDisruptions() {
        return true; // both roles; written out so the rule is findable and the next role has to decide
    }

    /** Ask whether a scanned boarding pass is still good (ADR-0056). Both roles; there is no gate-agent role yet. */
    public boolean mayVerifyBoardingPasses() {
        return true;
    }

    public boolean mayCreateStaff() {
        return createsStaff;
    }
}
