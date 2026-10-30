package com.skyhub.flightsearch.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.skyhub.flightsearch.application.FlightStatusBoard;
import com.skyhub.flightsearch.domain.FlightStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerSeekAware;

class FlightDisruptionListenerTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/flight-disrupted.v1.example.json");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FlightStatusBoard board = new FlightStatusBoard(Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC), registry);
    private final FlightDisruptionListener listener = new FlightDisruptionListener(board, registry);

    @Test
    void should_put_what_it_reads_on_the_board() throws Exception {
        listener.onMessage(Files.readString(EXAMPLE));

        assertThat(board.statusOf("SK501", LocalDate.of(2026, 11, 2)).kind()).isEqualTo(FlightStatus.Kind.DELAYED);
    }

    @Test
    void should_skip_a_message_it_cannot_read_instead_of_failing_and_blocking_the_ones_behind_it() {
        assertThatCode(() -> listener.onMessage("not json")).doesNotThrowAnyException();

        assertThat(board.size()).isZero();
        assertThat(registry.get("skyhub.flight.status.events").tag("outcome", "invalid").counter().count()).isEqualTo(1);
    }

    @Test
    void should_skip_event_kinds_it_does_not_act_on_so_the_topic_can_grow() {
        assertThatCode(() -> listener.onMessage("{\"type\":\"flight.other\"}")).doesNotThrowAnyException();

        assertThat(board.size()).isZero();
    }

    @Test
    void should_start_every_partition_from_the_beginning_so_a_new_instance_learns_every_disrupted_flight() {
        ConsumerSeekAware.ConsumerSeekCallback seek = mock(ConsumerSeekAware.ConsumerSeekCallback.class);
        Set<TopicPartition> partitions = Set.of(new TopicPartition("skyhub.flight.disruptions", 0));

        listener.onPartitionsAssigned(Map.of(new TopicPartition("skyhub.flight.disruptions", 0), 42L), seek);

        verify(seek).seekToBeginning(partitions);
    }
}
