package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.Timetable;
import com.skyhub.flightsearch.persistence.FlightScheduleRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Loads the timetable at startup and reloads it periodically. Each reload builds a new immutable
 * {@link Timetable} and publishes it with a single volatile write (copy-on-write): readers never
 * lock and never see a half-built index. If a reload fails, the previous snapshot keeps serving.
 */
@Component
public class TimetableCache implements TimetableSource {

    private static final Logger log = LoggerFactory.getLogger(TimetableCache.class);

    private final FlightScheduleRepository schedules;
    private final Clock clock;
    private volatile Timetable current;
    private volatile long lastRefreshEpochSeconds;

    public TimetableCache(FlightScheduleRepository schedules, MeterRegistry registry, Clock clock) {
        this.schedules = schedules;
        this.clock = clock;
        this.current = load(); // fail fast: no timetable, no service
        Gauge.builder("skyhub.timetable.flights", this, cache -> cache.current.size())
                .description("Flights in the in-memory timetable")
                .register(registry);
        Gauge.builder("skyhub.timetable.last.refresh", this, cache -> cache.lastRefreshEpochSeconds)
                .description("Unix time of the last successful timetable load")
                .baseUnit("seconds")
                .register(registry);
    }

    @Override
    public Timetable current() {
        return current;
    }

    @Scheduled(fixedDelayString = "${skyhub.timetable.refresh-interval:PT5M}",
            initialDelayString = "${skyhub.timetable.refresh-interval:PT5M}")
    public void refresh() {
        try {
            current = load();
        } catch (RuntimeException e) {
            log.warn("Timetable refresh failed; keeping the previous snapshot of {} flights", current.size(), e);
        }
    }

    private Timetable load() {
        Timetable timetable = Timetable.of(schedules.findAll());
        lastRefreshEpochSeconds = clock.instant().getEpochSecond();
        log.atInfo().addKeyValue("timetable.flights", timetable.size()).log("Timetable loaded");
        return timetable;
    }
}
