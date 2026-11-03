package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * When will a flight really leave? A recorded delay beats the timetable (ADR-0014); with none, the timetable is asked. One place for
 * the answer, so the rules that depend on the departure time (the check-in and boarding windows, ADR-0013, ADR-0063) never each
 * re-implement it. A cancelled flight has no departure, and what that means is the caller's to decide (check-in refuses it, check-out
 * always allows it, boarding refuses it): ADR-0075.
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
     * @return the time the flight is now expected to leave, or empty when it is cancelled (it will not leave, and the timetable is not asked)
     * @throws FlightNotFoundException when the flight does not operate on that date
     * @throws FlightCatalogUnavailableException when the timetable cannot be checked right now
     */
    public Optional<Instant> of(String flightNumber, LocalDate departureDate) {
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(flightNumber, departureDate).orElse(null);
        if (disruption != null) {
            return disruption.isCancelled() ? Optional.empty() : Optional.of(disruption.getRevisedDeparture());
        }
        return Optional.of(flights.departure(flightNumber, departureDate)
                .orElseThrow(() -> new FlightNotFoundException(flightNumber + " does not operate on " + departureDate)));
    }
}
