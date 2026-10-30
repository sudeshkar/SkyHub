package com.skyhub.flightsearch.messaging;

import com.skyhub.flightsearch.application.FlightStatusBoard;
import com.skyhub.flightsearch.domain.FlightDisruption;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Keeps the {@link FlightStatusBoard} current from the compacted flight-disruptions topic (ADR-0025).
 *
 * <p>Two choices make this correct for in-memory state. First, <b>every instance reads every message</b>: the consumer group id
 * is unique per instance, so instances do not share the work, which would leave each with only part of the picture. Second,
 * <b>every start reads from the beginning</b>: on assignment it seeks to the start of each partition, so a freshly started
 * instance learns the current state of every disrupted flight, however old, because compaction keeps the latest event per
 * flight and date. A message that cannot be read is skipped and counted, so one bad message never blocks the ones behind it.
 */
@Component
@ConditionalOnProperty(name = "skyhub.flight-status.listener.enabled", havingValue = "true", matchIfMissing = true)
public class FlightDisruptionListener extends AbstractConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(FlightDisruptionListener.class);

    private final FlightStatusBoard board;
    private final Counter invalid;

    public FlightDisruptionListener(FlightStatusBoard board, MeterRegistry registry) {
        this.board = board;
        this.invalid = Counter.builder("skyhub.flight.status.events").tag("outcome", "invalid")
                .description("Flight disruption events read from Kafka, by outcome").register(registry);
    }

    @KafkaListener(topics = "${skyhub.flight-status.topic:skyhub.flight.disruptions}",
            groupId = "flight-search-#{T(java.util.UUID).randomUUID()}", idIsGroup = false)
    public void onMessage(String message) {
        try {
            if (!FlightDisruption.TYPE.equals(FlightDisruptionParser.typeOf(message))) {
                return;   // another kind of event: the topic may carry more kinds later
            }
            board.apply(FlightDisruptionParser.parse(message));
        } catch (InvalidFlightEventException e) {
            invalid.increment();
            log.warn("Skipped a flight disruption message that could not be read: {}", e.getMessage());
        }
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        super.onPartitionsAssigned(assignments, callback);
        callback.seekToBeginning(assignments.keySet());
    }
}
