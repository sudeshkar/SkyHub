package com.skyhub.notification.messaging;

import com.skyhub.notification.application.NotificationService;
import com.skyhub.notification.domain.AccountEmailChangeLinkEvent;
import com.skyhub.notification.domain.AccountEmailChangedEvent;
import com.skyhub.notification.domain.AccountPasswordLinkEvent;
import com.skyhub.notification.domain.BookingCancelledEvent;
import com.skyhub.notification.domain.BookingConfirmedEvent;
import com.skyhub.notification.domain.BookingCheckInOpenEvent;
import com.skyhub.notification.domain.BookingCheckedInEvent;
import com.skyhub.notification.domain.BookingDisruptedEvent;
import com.skyhub.notification.domain.BookingDisruptionWithdrawnEvent;
import com.skyhub.notification.domain.BookingRebookedEvent;
import com.skyhub.notification.domain.BookingRefundCompletedEvent;
import com.skyhub.notification.domain.EventParser;
import com.skyhub.notification.domain.InvalidEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.stereotype.Component;

/**
 * Reads the booking events topic (ADR-0019). Kinds of event this service does not act on are skipped, so the topic
 * can gain new event types without breaking it.
 *
 * <p>A message that fails (usually because the mail server did not answer) does not hold up the messages behind it (ADR-0065): it is
 * copied to a delayed retry topic and the consumer carries on. Each retry waits longer than the last, so an outage of some minutes is
 * ridden out without a person. When the attempts run out the message is parked on {@code <topic>.DLT}, the name the parked-message
 * tooling reads (ADR-0032). A message that is not an event at all can never succeed and is parked at once, with no retries. Handling an
 * event twice is harmless because each event id is recorded once, which is also what makes retrying safe.
 */
@Component
public class BookingEventsListener {

    private static final Logger log = LoggerFactory.getLogger(BookingEventsListener.class);

    private final NotificationService notifications;

    public BookingEventsListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @RetryableTopic(
            attempts = "${skyhub.notifications.retry.attempts:5}",
            backOff = @BackOff(delayString = "${skyhub.notifications.retry.delay-ms:10000}",
                    multiplierString = "${skyhub.notifications.retry.multiplier:6.0}",
                    maxDelayString = "${skyhub.notifications.retry.max-delay-ms:900000}",
                    jitterString = "${skyhub.notifications.retry.jitter-ms:1000}"),
            retryTopicSuffix = ".retry",
            dltTopicSuffix = ".DLT",
            // The retry and parked topics must have as many partitions as the events topic, because a message keeps its partition number.
            numPartitions = "${skyhub.notifications.retry.partitions:3}",
            exclude = InvalidEventException.class,
            // The parked messages are read by the replay tool (ADR-0032); nothing here may consume them.
            autoStartDltHandler = "false")
    @KafkaListener(topics = "${skyhub.notifications.topic:skyhub.booking.events}")
    public void onMessage(String message) {
        String type = EventParser.typeOf(message);
        switch (type) {
            case BookingConfirmedEvent.TYPE ->
                    notifications.bookingConfirmed(EventParser.parse(message, BookingConfirmedEvent.class));
            case BookingCancelledEvent.TYPE ->
                    notifications.bookingCancelled(EventParser.parse(message, BookingCancelledEvent.class));
            case BookingRebookedEvent.TYPE ->
                    notifications.bookingRebooked(EventParser.parse(message, BookingRebookedEvent.class));
            case BookingDisruptedEvent.TYPE ->
                    notifications.bookingDisrupted(EventParser.parse(message, BookingDisruptedEvent.class));
            case BookingDisruptionWithdrawnEvent.TYPE ->
                    notifications.bookingDisruptionWithdrawn(EventParser.parse(message, BookingDisruptionWithdrawnEvent.class));
            case BookingCheckedInEvent.TYPE ->
                    notifications.checkedIn(EventParser.parse(message, BookingCheckedInEvent.class));
            case BookingCheckInOpenEvent.TYPE ->
                    notifications.checkInOpen(EventParser.parse(message, BookingCheckInOpenEvent.class));
            case BookingRefundCompletedEvent.TYPE ->
                    notifications.refundCompleted(EventParser.parse(message, BookingRefundCompletedEvent.class));
            case AccountPasswordLinkEvent.TYPE ->
                    notifications.passwordLink(EventParser.parse(message, AccountPasswordLinkEvent.class));
            case AccountEmailChangeLinkEvent.TYPE ->
                    notifications.emailChangeLink(EventParser.parse(message, AccountEmailChangeLinkEvent.class));
            case AccountEmailChangedEvent.TYPE ->
                    notifications.emailChanged(EventParser.parse(message, AccountEmailChangedEvent.class));
            default -> log.atDebug().addKeyValue("event.type", type).log("Event type not handled here; skipped");
        }
    }
}
