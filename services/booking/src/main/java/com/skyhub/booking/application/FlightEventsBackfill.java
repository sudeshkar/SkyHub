package com.skyhub.booking.application;

import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.persistence.DisruptionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * One-off: republishes every current disruption to the flight-disruptions topic (ADR-0025). Disruptions recorded before that
 * topic existed are in the database but were never published, so a search service would never learn of them. Switch it on for
 * one start with {@code skyhub.outbox.backfill-flight-events=true}. Republishing is harmless: the topic is compacted (the
 * latest event per flight and date wins) and consumers apply only what is newer than what they hold. Off by default, so a
 * normal start does not write anything.
 */
@Component
@ConditionalOnProperty(name = "skyhub.outbox.backfill-flight-events", havingValue = "true")
public class FlightEventsBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlightEventsBackfill.class);

    private final DisruptionRepository disruptions;
    private final FlightEvents events;
    private final Clock clock;
    private final TransactionOperations transactions;

    public FlightEventsBackfill(DisruptionRepository disruptions, FlightEvents events, Clock clock, TransactionOperations transactions) {
        this.disruptions = disruptions;
        this.events = events;
        this.clock = clock;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.atInfo().addKeyValue("backfill.published", run()).log("Republished current flight disruptions");
    }

    /** @return how many disruptions were queued for publishing */
    public int run() {
        LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
        return transactions.execute(status -> {
            List<Disruption> current = disruptions.findByDepartureDateGreaterThanEqual(from);
            current.forEach(events::flightDisrupted);
            return current.size();
        });
    }
}
