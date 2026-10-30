package com.skyhub.booking.application;

import com.skyhub.booking.application.CheckInReminderMetrics.Outcome;
import com.skyhub.booking.application.CheckInReminderStore.FlightDay;
import com.skyhub.booking.application.CheckInReminderStore.Waiting;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Queues a "check-in is open" reminder for every booking whose check-in window has just opened (ADR-0029).
 *
 * <p>Built to stay cheap as bookings grow. The sweep works <em>per flight</em>, not per booking: it finds the few distinct
 * flights that have someone waiting, works out from the flight's departure (its revised time if delayed) whether its window is
 * open, and only then reads that flight's bookings, a page at a time. Flights not yet open or already closed cost one
 * lookup, however many people are booked. Each reminder is claimed by inserting a row with a primary key, so several
 * instances can sweep together and a reminder is still queued once.
 */
@Service
public class CheckInReminders {

    private static final Logger log = LoggerFactory.getLogger(CheckInReminders.class);

    static final int PAGE_SIZE = 200;
    static final int MAX_PER_SWEEP = 5_000;

    private final CheckInReminderStore store;
    private final PnrRepository pnrs;
    private final DisruptionRepository disruptions;
    private final FlightCatalog flights;
    private final BookingEvents events;
    private final CheckInWindow window;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final CheckInReminderMetrics metrics;

    public CheckInReminders(CheckInReminderStore store, PnrRepository pnrs, DisruptionRepository disruptions,
                            FlightCatalog flights, BookingEvents events, CheckInWindow window, Clock clock,
                            TransactionOperations transactions, CheckInReminderMetrics metrics) {
        this.store = store;
        this.pnrs = pnrs;
        this.disruptions = disruptions;
        this.flights = flights;
        this.events = events;
        this.window = window;
        this.clock = clock;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    /** @return how many reminders were queued */
    public int sweep() {
        Instant now = clock.instant();
        // Yesterday's date too: a flight delayed past midnight still belongs to its scheduled date.
        LocalDate from = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate to = now.plus(window.opensBefore()).atZone(ZoneOffset.UTC).toLocalDate();
        int queued = 0;
        for (FlightDay flight : store.flightsWithBookingsWaiting(from, to)) {
            Optional<Instant> departure = departureOf(flight);
            if (departure.isEmpty() || !isOpen(departure.get(), now)) {
                continue;
            }
            queued += remind(flight, departure.get(), now, MAX_PER_SWEEP - queued);
            if (queued >= MAX_PER_SWEEP) {
                break;   // the rest wait for the next sweep: nothing is lost, they are still unclaimed
            }
        }
        if (queued > 0) {
            metrics.record(Outcome.SENT, queued);
            log.atInfo().addKeyValue("checkin.reminders", queued).log("Check-in reminders queued");
        }
        return queued;
    }

    private boolean isOpen(Instant departure, Instant now) {
        return !now.isBefore(departure.minus(window.opensBefore())) && now.isBefore(departure.minus(window.closesBefore()));
    }

    private int remind(FlightDay flight, Instant departure, Instant now, int room) {
        int queued = 0;
        while (queued < room) {
            List<Waiting> page = store.waitingOn(flight, Math.min(PAGE_SIZE, room - queued));
            if (page.isEmpty()) {
                break;
            }
            Integer done = transactions.execute(status -> deliver(page, flight, departure, now));
            queued += done == null ? 0 : done;
        }
        return queued;
    }

    /** One transaction per page: each reminder is claimed and queued together, so none is claimed without being queued. */
    private int deliver(List<Waiting> page, FlightDay flight, Instant departure, Instant now) {
        int queued = 0;
        for (Waiting waiting : page) {
            if (!store.claim(waiting, now)) {
                continue;   // another instance got there first
            }
            Pnr pnr = pnrs.findByLocator(waiting.locator()).orElse(null);
            if (pnr == null || pnr.getStatus() != PnrStatus.CONFIRMED) {
                continue;   // cancelled since we looked
            }
            events.checkInOpened(pnr, pnr.getSegments().get(waiting.segmentIndex()), departure,
                    departure.minus(window.closesBefore()));
            queued++;
        }
        return queued;
    }

    /** A disruption beats the timetable, as it does for check-in itself. Empty when the time cannot be known right now. */
    private Optional<Instant> departureOf(FlightDay flight) {
        Disruption disruption = disruptions.findByFlightNumberAndDepartureDate(flight.flightNumber(), flight.departureDate()).orElse(null);
        if (disruption != null && disruption.isCancelled()) {
            return Optional.empty();
        }
        if (disruption != null) {
            return Optional.of(disruption.getRevisedDeparture());
        }
        try {
            return flights.departure(flight.flightNumber(), flight.departureDate());
        } catch (FlightCatalogUnavailableException unavailable) {
            metrics.record(Outcome.FLIGHT_UNAVAILABLE, 1);
            log.atWarn().addKeyValue("flight", flight.flightNumber()).log("Flight time unknown; reminders for it wait for the next sweep");
            return Optional.empty();
        }
    }
}
