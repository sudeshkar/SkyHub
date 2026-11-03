package com.skyhub.booking.domain;

/** What a staff member may do (ADR-0017). Everything not listed here is denied. */
public enum StaffRole {
    /** Operations staff: record delays and cancellations, rebook, and work the gate. */
    OPS(false, true),
    /** Administrators: everything operations can do, and create staff accounts. */
    ADMIN(true, true),
    /** A gate agent (ADR-0060): checks a boarding pass and boards a passenger, and nothing else. */
    GATE(false, false);

    private final boolean createsStaff;
    private final boolean runsOperations;

    StaffRole(boolean createsStaff, boolean runsOperations) {
        this.createsStaff = createsStaff;
        this.runsOperations = runsOperations;
    }

    /** Who is booked on a flight (without names or contact details), so a disruption can be worked out. */
    public boolean mayViewAffectedBookings() {
        return runsOperations;
    }

    /** Move a booking off a disrupted flight. Every move is audited with the person's name. */
    public boolean mayRebook() {
        return runsOperations;
    }

    public boolean mayRecordDisruptions() {
        return runsOperations; // written out so the rule is findable and the next role has to decide
    }

    /** Ask whether a scanned boarding pass is still good (ADR-0056). Every role. */
    public boolean mayVerifyBoardingPasses() {
        return true;
    }

    /** Board a passenger with a good pass (ADR-0060). Every role: this is the gate agent's whole job. */
    public boolean mayBoardPassengers() {
        return true;
    }

    /**
     * Take back a boarding that was recorded by mistake (ADR-0064). Operations and administrators only: the gate agent who made a scan must
     * not be the one who can erase it. Written out like the others so the next role has to decide.
     */
    public boolean mayUndoBoarding() {
        return runsOperations;
    }

    /**
     * See who has boarded a flight (ADR-0065). It lists booking references, so a gate agent does not get it (ADR-0060). Written out like
     * the others so the next role has to decide.
     */
    public boolean mayViewFlightBoarding() {
        return runsOperations;
    }

    /**
     * Board a passenger after the gate has closed (ADR-0076). It overrules the gate, so the person at the gate does not get it. Written out
     * like the others so the next role has to decide.
     */
    public boolean mayBoardLatePassengers() {
        return runsOperations;
    }

    public boolean mayCreateStaff() {
        return createsStaff;
    }
}
