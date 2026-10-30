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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delays and cancellations (ADR-0014). Recording is idempotent: the same flight and date always has at most one
 * record, and recording again replaces it. Who may record is the security layer's concern; who did is stored with the record.
 */
@Service
public class DisruptionService {

    private static final Logger log = LoggerFactory.getLogger(DisruptionService.class);

    private final DisruptionRepository disruptions;
    private final PnrRepository pnrs;
    private final Clock clock;
    private final DisruptionMetrics metrics;
    private final BookingEvents events;
    private final TransactionOperations transactions;

    public DisruptionService(DisruptionRepository disruptions, PnrRepository pnrs, Clock clock, DisruptionMetrics metrics,
                             BookingEvents events, TransactionOperations transactions) {
        this.events = events;
        this.transactions = transactions;
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
        int affected;
        try {
            affected = transactions.execute(status -> saveAndTellTravellers(command));
        } catch (DataIntegrityViolationException createdMeanwhile) {
            // Another operator recorded this flight between our lookup and our insert: update theirs instead.
            affected = transactions.execute(status -> saveAndTellTravellers(command));
        }
        metrics.record(command.type());
        log.atInfo()
                .addKeyValue("flight", command.flightNumber())
                .addKeyValue("disruption.recorded_by", command.recordedBy())
                .addKeyValue("disruption.type", command.type())
                .addKeyValue("disruption.affected_bookings", affected)
                .log("Disruption recorded");
        return affected;
    }

    /**
     * One transaction: the record, and an event for every active booking on the flight (ADR-0019). Either both exist or
     * neither does. Recording something that changes nothing a traveller could see sends nothing, so an operations tool
     * that retries does not email everyone again.
     *
     * @return how many active bookings are on the flight
     */
    private int saveAndTellTravellers(RecordDisruptionCommand command) {
        Instant now = clock.instant();
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(command.flightNumber(), command.departureDate())
                .orElse(null);
        boolean changed = disruption == null
                || !disruption.sameAs(command.type(), command.revisedDeparture(), command.reason());
        if (disruption == null) {
            disruption = command.type() == DisruptionType.DELAYED
                    ? Disruption.delayed(command.flightNumber(), command.departureDate(), command.revisedDeparture(), command.reason(), now)
                    : Disruption.cancelled(command.flightNumber(), command.departureDate(), command.reason(), now);
        } else if (command.type() == DisruptionType.DELAYED) {
            disruption.becomeDelayed(command.revisedDeparture(), command.reason(), now);
        } else {
            disruption.becomeCancelled(command.reason(), now);
        }
        disruption.recordedBy(command.recordedBy());
        disruptions.save(disruption);

        List<Pnr> active = pnrs.findActiveOnFlight(command.flightNumber(), command.departureDate());
        if (changed) {
            for (Pnr pnr : active) {
                events.bookingDisrupted(pnr, disruption);
            }
        }
        return active.size();
    }

    /**
     * The active bookings on a flight, reduced to what re-accommodation needs (ADR-0022): no names, no contact details.
     * Read-only, so asking never changes anything.
     */
    public List<AffectedBooking> affectedBookings(String flightNumber, LocalDate departureDate) {
        return transactions.execute(status -> pnrs.findActiveOnFlight(flightNumber, departureDate).stream()
                .map(pnr -> new AffectedBooking(pnr.getLocator(), pnr.getPassengers().size(),
                        indexOf(pnr, flightNumber, departureDate), List.copyOf(pnr.getSegments())))
                .toList());
    }

    private static int indexOf(Pnr pnr, String flightNumber, LocalDate departureDate) {
        List<PnrSegment> segments = pnr.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).flightNumber().equals(flightNumber) && segments.get(i).departureDate().equals(departureDate)) {
                return i;
            }
        }
        throw new IllegalStateException("The booking was found for a flight it does not have");
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
