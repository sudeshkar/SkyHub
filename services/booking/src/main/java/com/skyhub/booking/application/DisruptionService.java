package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.DisruptionType;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Delays and cancellations (ADR-0014). Recording is idempotent: the same flight and date always has at most one
 * record, and recording again replaces it. Who may record is the security layer's concern, not this class's.
 */
@Service
public class DisruptionService {

    private static final Logger log = LoggerFactory.getLogger(DisruptionService.class);

    private final DisruptionRepository disruptions;
    private final PnrRepository pnrs;
    private final Clock clock;
    private final DisruptionMetrics metrics;

    public DisruptionService(DisruptionRepository disruptions, PnrRepository pnrs, Clock clock, DisruptionMetrics metrics) {
        this.disruptions = disruptions;
        this.pnrs = pnrs;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** @return how many active bookings are on the flight, so operations can see the size of it */
    public int record(RecordDisruptionCommand command) {
        if (command.type() == DisruptionType.CANCELLED && command.revisedDeparture() != null) {
            throw new InvalidBookingException("A cancelled flight cannot have a revised departure time");
        }
        try {
            save(command);
        } catch (DataIntegrityViolationException createdMeanwhile) {
            // Another operator recorded this flight between our lookup and our insert: update theirs instead.
            save(command);
        }
        metrics.record(command.type());
        long affected = pnrs.countActiveOnFlight(command.flightNumber(), command.departureDate());
        log.atInfo()
                .addKeyValue("flight", command.flightNumber())
                .addKeyValue("disruption.type", command.type())
                .addKeyValue("disruption.affected_bookings", affected)
                .log("Disruption recorded");
        return Math.toIntExact(affected);
    }

    private void save(RecordDisruptionCommand command) {
        Instant now = clock.instant();
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(command.flightNumber(), command.departureDate())
                .orElse(null);
        if (disruption == null) {
            disruption = command.type() == DisruptionType.DELAYED
                    ? Disruption.delayed(command.flightNumber(), command.departureDate(), command.revisedDeparture(), command.reason(), now)
                    : Disruption.cancelled(command.flightNumber(), command.departureDate(), command.reason(), now);
        } else if (command.type() == DisruptionType.DELAYED) {
            disruption.becomeDelayed(command.revisedDeparture(), command.reason(), now);
        } else {
            disruption.becomeCancelled(command.reason(), now);
        }
        disruptions.save(disruption);
    }

    /** The disrupted flights of a booking, in booking order. Empty when all is well. */
    public List<BookingDisruption> forBooking(String locator) {
        Pnr pnr = pnrs.findByLocator(locator).orElseThrow(() -> new PnrNotFoundException("No booking matches this reference"));
        List<BookingDisruption> items = new ArrayList<>();
        List<PnrSegment> segments = pnr.getSegments();
        for (int index = 0; index < segments.size(); index++) {
            PnrSegment segment = segments.get(index);
            int position = index;
            disruptions.findByFlightNumberAndDepartureDate(segment.flightNumber(), segment.departureDate())
                    .ifPresent(d -> items.add(new BookingDisruption(position, d.getFlightNumber(), d.getDepartureDate(),
                            d.getType(), d.getRevisedDeparture(), d.getReason())));
        }
        return items;
    }
}
