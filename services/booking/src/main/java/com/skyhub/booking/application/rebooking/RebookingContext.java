package com.skyhub.booking.application.rebooking;

import com.skyhub.booking.application.FlightInfo;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrSegment;
import java.util.Optional;

/**
 * Everything the rebooking rules may look at, loaded once by the service so no rule queries anything itself. Rules stay
 * pure functions of this value, which makes each one trivial to test and cheap to add.
 */
public record RebookingContext(Pnr pnr, int segmentIndex, PnrSegment from, PnrSegment to, FlightInfo fromInfo,
                               FlightInfo toInfo, Optional<Disruption> fromDisruption, Optional<Disruption> toDisruption,
                               boolean checkedIn, Optional<Neighbour> previous, Optional<Neighbour> next) {

    /** A flight of the same booking either side of the one being moved, with what is known about it (ADR-0036). */
    public record Neighbour(PnrSegment segment, FlightInfo info, Optional<Disruption> disruption) {
    }

    /** A booking whose neighbouring flights are not considered: the single-flight case, and rules that do not look at them. */
    public RebookingContext(Pnr pnr, int segmentIndex, PnrSegment from, PnrSegment to, FlightInfo fromInfo, FlightInfo toInfo,
                            Optional<Disruption> fromDisruption, Optional<Disruption> toDisruption, boolean checkedIn) {
        this(pnr, segmentIndex, from, to, fromInfo, toInfo, fromDisruption, toDisruption, checkedIn, Optional.empty(), Optional.empty());
    }
}
