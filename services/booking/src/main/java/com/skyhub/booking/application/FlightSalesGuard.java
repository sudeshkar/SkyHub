package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightCancelledException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Seats are not sold on a flight the airline has cancelled (ADR-0025): no price, no hold, no booking, and so no charge. A
 * delayed flight is still sold, because a traveller can choose it knowing the new time. One lookup per distinct flight of
 * the trip (at most four), made before anything is charged or locked.
 */
@Component
public class FlightSalesGuard {

    private final DisruptionRepository disruptions;

    public FlightSalesGuard(DisruptionRepository disruptions) {
        this.disruptions = disruptions;
    }

    /** @throws FlightCancelledException naming the first cancelled flight of the trip */
    public void requireSellable(Collection<PnrSegment> trip) {
        Set<PnrSegment> seen = new HashSet<>();
        for (PnrSegment flight : trip) {
            if (seen.add(flight)
                    && disruptions.findByFlightNumberAndDepartureDate(flight.flightNumber(), flight.departureDate())
                            .filter(Disruption::isCancelled).isPresent()) {
                throw new FlightCancelledException(flight.flightNumber() + " on " + flight.departureDate()
                        + " is cancelled and cannot be booked");
            }
        }
    }
}
