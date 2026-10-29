package com.skyhub.flightsearch.application;

import com.skyhub.flightsearch.domain.Timetable;
import com.skyhub.flightsearch.persistence.FlightScheduleRepository;
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
    private volatile Timetable current;

    public TimetableCache(FlightScheduleRepository schedules) {
        this.schedules = schedules;
        this.current = load(); // fail fast: no timetable, no service
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
        log.info("Timetable loaded: {} flights", timetable.size());
        return timetable;
    }
}
