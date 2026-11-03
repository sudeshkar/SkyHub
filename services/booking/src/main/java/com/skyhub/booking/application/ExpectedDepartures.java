package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * When will a flight really leave? A recorded delay beats the timetable (ADR-0014); with none, the timetable is asked. One place for
 * the answer, so the rules that depend on the departure time (the boarding window, ADR-0063) never each re-implement it.
 * A cancelled flight is not handled here: the callers that care have already refused it.
 */
@Component
public class ExpectedDepartures {

    private final DisruptionRepository disruptions;
    private final FlightCatalog flights;

    public ExpectedDepartures(DisruptionRepository disruptions, FlightCatalog flights) {
        this.disruptions = disruptions;
        this.flights = flights;
    }

    /**
     * @throws FlightNotFoundException when the flight does not operate on that date
     * @throws FlightCatalogUnavailableException when the timetable cannot be checked right now
     */
    public Instant of(String flightNumber, LocalDate departureDate) {
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(flightNumber, departureDate).orElse(null);
        if (disruption != null && !disruption.isCancelled()) {
            return disruption.getRevisedDeparture();
        }
        return flights.departure(flightNumber, departureDate)
                .orElseThrow(() -> new FlightNotFoundException(flightNumber + " does not operate on " + departureDate));
    }
}
