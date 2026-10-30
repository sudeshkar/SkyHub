package com.skyhub.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The flight-disruptions topic is <em>compacted</em> (ADR-0025): Kafka keeps the latest event for each flight and date
 * indefinitely instead of deleting old messages after a week. A flight-search instance that starts a year later still
 * replays the current state of every disrupted flight. Created here because a topic that is first written to is created
 * with the broker's default (delete after retention), which would silently forget old disruptions.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "skyhub.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class KafkaTopicsConfig {

    @Bean
    NewTopic flightDisruptionsTopic() {
        return TopicBuilder.name("skyhub.flight.disruptions").partitions(1).replicas(1).compact().build();
    }
}
