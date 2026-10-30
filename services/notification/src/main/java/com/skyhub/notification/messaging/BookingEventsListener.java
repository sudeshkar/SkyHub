package com.skyhub.notification.messaging;

import com.skyhub.notification.application.NotificationService;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.EventParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads the booking events topic (ADR-0019). Kinds of event this service does not act on are skipped, so the topic
 * can gain new event types without breaking it. A message that is not an event at all throws, and ends up parked on
 * the dead-letter topic (see KafkaErrorHandling) instead of being dropped without a trace.
 */
@Component
public class BookingEventsListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventsListener.class);

    private final NotificationService notifications;

    public BookingEventsListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @KafkaListener(topics = "${skyhub.notifications.topic:skyhub.booking.events}")
    public void onMessage(String message) {
        String type = EventParser.typeOf(message);
        if (BookingConfirmedEvent.TYPE.equals(type)) {
            notifications.bookingConfirmed(EventParser.parse(message));
        } else {
            log.atDebug().addKeyValue("event.type", type).log("Event type not handled here; skipped");
        }
    }
}
