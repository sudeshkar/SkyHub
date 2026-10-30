package com.skyhub.booking.application;

import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PnrSegment;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The one place a price is worked out (ADR-0015): the fare of each flight times the passengers. The website's
 * quote and the booking's charge both come from here, so they cannot disagree. Fares come from the flight
 * catalog (which caches them), never from the request.
 */
@Service
public class PricingService {

    private final FlightCatalog flights;
    private final FlightSalesGuard guard;

    public PricingService(FlightCatalog flights, FlightSalesGuard guard) {
        this.flights = flights;
        this.guard = guard;
    }

    /**
     * @throws FlightNotFoundException when a flight does not operate on its date
     * @throws com.skyhub.booking.domain.FlightCancelledException when the airline has cancelled one of the flights
     * @throws InvalidBookingException when there is nothing to price or the flights are priced in different currencies
     */
    public Money quote(List<PnrSegment> segments, int passengers) {
        if (segments.isEmpty() || passengers < 1) {
            throw new InvalidBookingException("A trip needs at least one flight and one passenger");
        }
        guard.requireSellable(segments);   // no price for a flight that cannot be bought (ADR-0025)
        Money total = null;
        for (PnrSegment segment : segments) {
            Money fare = flights.find(segment.flightNumber(), segment.departureDate())
                    .orElseThrow(() -> new FlightNotFoundException(
                            segment.flightNumber() + " does not operate on " + segment.departureDate()))
                    .fare();
            if (total != null && !total.currency().equals(fare.currency())) {
                throw new InvalidBookingException(
                        "These flights are priced in different currencies (" + total.currency() + " and " + fare.currency()
                                + ") and cannot be booked together");
            }
            total = total == null ? fare : total.plus(fare);
        }
        return total.times(passengers);
    }
}
