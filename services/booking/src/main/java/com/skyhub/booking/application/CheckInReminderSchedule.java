package com.skyhub.booking.application;

import com.skyhub.booking.application.CheckInReminderMetrics.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer for {@link CheckInReminders}, kept apart so tests can run a sweep on demand and switch the timer off.
 * A failed sweep is counted and the next one tries again.
 */
@Component
@ConditionalOnProperty(name = "skyhub.booking.check-in-reminders.enabled", havingValue = "true", matchIfMissing = true)
public class CheckInReminderSchedule {

    private static final Logger log = LoggerFactory.getLogger(CheckInReminderSchedule.class);

    private final CheckInReminders reminders;
    private final CheckInReminderMetrics metrics;

    public CheckInReminderSchedule(CheckInReminders reminders, CheckInReminderMetrics metrics) {
        this.reminders = reminders;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${skyhub.booking.check-in-reminders.interval:PT5M}", initialDelayString = "PT30S")
    public void run() {
        try {
            reminders.sweep();
        } catch (RuntimeException e) {
            metrics.record(Outcome.FAILED, 1);
            log.warn("Check-in reminder sweep failed; will retry", e);
        }
    }
}
