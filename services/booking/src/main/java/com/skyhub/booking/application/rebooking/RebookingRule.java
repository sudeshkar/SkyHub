package com.skyhub.booking.application.rebooking;

import java.util.Optional;

/**
 * One reason a rebooking may be refused. The service runs every rule it is given and stops at the first refusal, so a
 * new restriction is a new class added to {@link RebookingRules#defaults()} and nothing existing is edited (open/closed).
 */
@FunctionalInterface
public interface RebookingRule {

    /** @return why the move is not allowed, written for the staff member who asked, or empty if this rule is satisfied */
    Optional<String> refusal(RebookingContext context);
}
