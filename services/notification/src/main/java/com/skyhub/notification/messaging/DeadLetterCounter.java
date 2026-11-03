package com.skyhub.notification.messaging;

import com.skyhub.notification.application.NotificationMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Counts each message that arrives on the dead-letter topic (ADR-0065), so the alert that tells a person still fires now that parking is
 * done by the retry topics and no code of ours sits at the moment a message is parked.
 *
 * <p>It reads in its own consumer group and starts at the end of the topic, so it counts what is parked from now on and never the
 * backlog that was already waiting: starting this service does not raise the alert again for messages a person has already been told
 * about. It takes nothing from the topic, so the parked-message tooling (ADR-0032, ADR-0037) sees every message as before. It logs the
 * event type and never the body.
 */
@Component
class DeadLetterCounter {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterCounter.class);

    private final NotificationMetrics metrics;

    DeadLetterCounter(NotificationMetrics metrics) {
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${skyhub.notifications.topic:skyhub.booking.events}.DLT", groupId = "notification-dead-letter-count",
            properties = "auto.offset.reset=latest")
    void onParked(ConsumerRecord<String, String> record) {
        metrics.deadLettered();
        log.atWarn().addKeyValue("event.type", ParkedMessage.of(record).eventType()).addKeyValue("kafka.partition", record.partition())
                .addKeyValue("kafka.offset", record.offset()).log("A notification was parked on the dead-letter topic");
    }
}
