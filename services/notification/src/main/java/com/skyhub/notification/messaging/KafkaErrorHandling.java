package com.skyhub.notification.messaging;

import com.skyhub.notification.application.NotificationMetrics;
import com.skyhub.notification.domain.InvalidEventException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens when handling a message fails (ADR-0019): it is tried again a few times, a little apart, because the
 * cause is usually temporary (the mail server). A message that is not an event at all is never retried. When retries
 * run out, the message is copied to {@code <topic>.DLT} with the reason in its headers, and the consumer carries on,
 * so one bad message cannot block every booking behind it. The alert on the dead-letter count tells a person.
 */
@Configuration(proxyBeanMethods = false)
class KafkaErrorHandling {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, NotificationMetrics metrics,
                                          @Value("${skyhub.notifications.retry-interval:PT2S}") Duration interval,
                                          @Value("${skyhub.notifications.retry-attempts:4}") int attempts) {
        // Any partition of the dead-letter topic: it need not have as many partitions as the original.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(record.topic() + ".DLT", -1));
        ConsumerRecordRecoverer counting = (record, error) -> {
            metrics.deadLettered();
            recoverer.accept(record, error);
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(counting, new FixedBackOff(interval.toMillis(), attempts - 1L));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
