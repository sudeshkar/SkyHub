package com.skyhub.booking.application.rebooking;

import java.util.List;

public final class RebookingRules {

    private RebookingRules() {
    }

    /** The rules every rebooking must satisfy, cheapest first. To add a restriction, write a rule and list it here. */
    public static List<RebookingRule> defaults() {
        return List.of(new FlightMustBeDisrupted(), new MustNotBeCheckedIn(), new TargetMustNotBeCancelled(),
                new MustStayOnTheSameRoute(), new MustNotLeaveEarlier(), new MustKeepTheConnections());
    }
}
