package com.skyhub.booking.application.rebooking;

import java.util.List;

/**
 * One move's result. {@code detail} says why a move did not happen and {@code seats} (in passenger order) are set only when it
 * did or already had; neither ever holds a name or an address.
 */
public record MoveResult(String locator, int segmentIndex, MoveOutcome outcome, String detail, List<String> seats) {

    static MoveResult of(RebookCommand command, MoveOutcome outcome, String detail) {
        return new MoveResult(command.locator(), command.segmentIndex(), outcome, detail, null);
    }
}
