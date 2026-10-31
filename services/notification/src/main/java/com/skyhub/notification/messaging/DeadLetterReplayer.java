package com.skyhub.notification.messaging;

import com.skyhub.notification.application.NotificationMetrics;
import com.skyhub.notification.application.SkippedLetters;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Puts messages parked on the dead-letter topic back on the booking events topic (ADR-0032), once the cause is fixed.
 *
 * <p>Progress is a consumer group's committed offsets, so nothing is deleted and nothing is replayed twice: a replay takes the
 * messages after the last one replayed. A message is committed only after the broker has confirmed it was republished, so a
 * failure part-way leaves the rest to the next replay. Replaying a message that was in fact handled is harmless, because the
 * consumer records each event id once. Each call takes at most {@value #MAX_PER_CALL} messages, and one replay runs at a time
 * in an instance.
 */
@Component
public class DeadLetterReplayer implements ParkedLookup {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayer.class);

    public static final int DEFAULT_PER_CALL = 100;
    public static final int MAX_PER_CALL = 1000;
    static final String GROUP = "notification-dead-letter-replay";
    private static final Duration POLL = Duration.ofSeconds(1);

    /** {@code parked} is how many messages are waiting; {@code messages} describes up to the requested number of them. */
    public record Parked(long parked, List<ParkedMessage> messages) {
    }

    /**
     * {@code skipped}: parked messages left out because a person listed them (ADR-0037). {@code busy}: another replay was already
     * running, so this one did nothing.
     */
    public record Replay(int replayed, int skipped, long remaining, boolean busy, String stoppedBecause) {
    }

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> kafka;
    private final NotificationMetrics metrics;
    private final SkippedLetters skipped;
    private final String topic;
    private final ReentrantLock running = new ReentrantLock();

    public DeadLetterReplayer(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka,
                              NotificationMetrics metrics, SkippedLetters skipped,
                              @Value("${skyhub.notifications.topic:skyhub.booking.events}") String topic) {
        this.skipped = skipped;
        this.consumers = consumers;
        this.kafka = kafka;
        this.metrics = metrics;
        this.topic = topic;
    }

    static int clamp(int limit) {
        return limit <= 0 ? DEFAULT_PER_CALL : Math.min(limit, MAX_PER_CALL);
    }

    private String deadLetterTopic() {
        return topic + ".DLT";
    }

    /** What is waiting. Takes nothing: no offset is committed. */
    public Parked peek(int limit) {
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "peek")) {
            Set<TopicPartition> partitions = assign(consumer);
            List<ConsumerRecord<String, String>> records = read(consumer, clamp(limit));
            Map<Integer, Set<Long>> skippedByPartition = skippedAmong(records);
            List<ParkedMessage> messages = records.stream()
                    .map(r -> ParkedMessage.of(r).withSkipped(skippedByPartition.getOrDefault(r.partition(), Set.of()).contains(r.offset())))
                    .toList();
            return new Parked(lag(consumer, partitions), messages);
        }
    }

    /** Whether a message is still waiting: the partition exists and the offset is not yet taken and is before its end. */
    @Override
    public boolean isWaiting(int partition, long offset) {
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "waiting")) {
            TopicPartition target = new TopicPartition(deadLetterTopic(), partition);
            List<PartitionInfo> info = consumer.partitionsFor(deadLetterTopic(), Duration.ofSeconds(10));
            if (info == null || info.stream().noneMatch(p -> p.partition() == partition)) {
                return false;
            }
            Set<TopicPartition> one = Set.of(target);
            OffsetAndMetadata committed = consumer.committed(one).get(target);
            long from = committed != null ? committed.offset() : consumer.beginningOffsets(one).getOrDefault(target, 0L);
            return offset >= from && offset < consumer.endOffsets(one).getOrDefault(target, 0L);
        }
    }

    /** One lookup per partition in the batch (not one per message): the skip list, restricted to the offsets that were read. */
    private Map<Integer, Set<Long>> skippedAmong(List<ConsumerRecord<String, String>> records) {
        Map<Integer, long[]> range = new HashMap<>();   // partition -> {lowest offset, highest offset}
        for (ConsumerRecord<String, String> record : records) {
            long[] bounds = range.computeIfAbsent(record.partition(), p -> new long[] {record.offset(), record.offset()});
            bounds[0] = Math.min(bounds[0], record.offset());
            bounds[1] = Math.max(bounds[1], record.offset());
        }
        Map<Integer, Set<Long>> found = new HashMap<>();
        range.forEach((partition, bounds) -> found.put(partition, skipped.skippedIn(partition, bounds[0], bounds[1])));
        return found;
    }

    /** Puts up to {@code limit} parked messages back, on the word of {@code by} (ADR-0052). */
    public Replay replay(int limit, Operator by) {
        if (!running.tryLock()) {
            return new Replay(0, 0, -1, true, "another replay is already running");
        }
        try (Consumer<String, String> consumer = consumers.createConsumer(GROUP, "replay")) {
            Set<TopicPartition> partitions = assign(consumer);
            Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
            int replayed = 0;
            String stopped = null;
            List<ConsumerRecord<String, String>> batch = read(consumer, clamp(limit));
            Map<Integer, Set<Long>> leftOut = skippedAmong(batch);
            Map<Integer, List<Long>> passed = new HashMap<>();   // skipped messages the replay has gone past
            for (ConsumerRecord<String, String> record : batch) {
                if (leftOut.getOrDefault(record.partition(), Set.of()).contains(record.offset())) {
                    // A person decided this can never succeed: not republished, but dealt with, so the offset moves past it.
                    done.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
                    passed.computeIfAbsent(record.partition(), p -> new ArrayList<>()).add(record.offset());
                    continue;
                }
                try {
                    kafka.send(topic, record.key(), record.value()).get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception failed) {
                    if (failed instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.atError().addKeyValue("dlt.partition", record.partition()).addKeyValue("dlt.offset", record.offset())
                            .setCause(failed).log("Could not put a parked message back; stopping the replay here");
                    stopped = "the broker did not accept a message";
                    break;
                }
                done.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
                replayed++;
            }
            if (!done.isEmpty()) {
                consumer.commitSync(done);   // only what the broker confirmed
                metrics.replayed(replayed);
                log.atInfo().addKeyValue("dlt.replayed", replayed).addKeyValue("ops.operator", by.name()).log("Parked messages put back");
            }
            int skippedCount = forgetPassed(passed, done);
            return new Replay(replayed, skippedCount, lag(consumer, partitions), false, stopped);
        } finally {
            running.unlock();
        }
    }

    /**
     * Removes the skip entries the replay has gone past, but only those below what was actually committed: a message after a stop
     * (the broker refusing one) was not passed, and stays listed for the next replay.
     */
    private int forgetPassed(Map<Integer, List<Long>> passed, Map<TopicPartition, OffsetAndMetadata> committed) {
        int count = 0;
        for (Map.Entry<Integer, List<Long>> entry : passed.entrySet()) {
            long committedTo = committed.entrySet().stream().filter(e -> e.getKey().partition() == entry.getKey())
                    .mapToLong(e -> e.getValue().offset()).max().orElse(0);
            List<Long> gone = entry.getValue().stream().filter(offset -> offset < committedTo).toList();
            skipped.forget(entry.getKey(), gone);
            count += gone.size();
        }
        if (count > 0) {
            metrics.skipped(count);
            log.atInfo().addKeyValue("dlt.skipped", count).log("Parked messages passed over, as decided by a person");
        }
        return count;
    }

    private Set<TopicPartition> assign(Consumer<String, String> consumer) {
        List<PartitionInfo> info = consumer.partitionsFor(deadLetterTopic(), Duration.ofSeconds(10));
        Set<TopicPartition> partitions = (info == null ? List.<PartitionInfo>of() : info).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).collect(Collectors.toSet());
        consumer.assign(partitions);
        // A partition with no committed offset starts from its beginning: nothing has been replayed from it yet.
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(partitions);
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata offset = committed.get(partition);
            if (offset == null) {
                consumer.seekToBeginning(List.of(partition));
            } else {
                consumer.seek(partition, offset.offset());
            }
        }
        return partitions;
    }

    /** Up to {@code limit} messages, oldest first within each partition, stopping when the topic has nothing more to give. */
    private List<ConsumerRecord<String, String>> read(Consumer<String, String> consumer, int limit) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        while (records.size() < limit) {
            var polled = consumer.poll(POLL);
            if (polled.isEmpty()) {
                break;
            }
            for (ConsumerRecord<String, String> record : polled) {
                if (records.size() >= limit) {
                    break;
                }
                records.add(record);
            }
        }
        return records;
    }

    /** Messages not yet replayed: what is on the topic beyond the committed offset (or from the start, where none is committed). */
    private long lag(Consumer<String, String> consumer, Set<TopicPartition> partitions) {
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(partitions);
        long total = 0;
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata offset = committed.get(partition);
            long from = offset == null ? beginning.getOrDefault(partition, 0L) : offset.offset();
            total += Math.max(0, end.getOrDefault(partition, 0L) - from);
        }
        return total;
    }
}
