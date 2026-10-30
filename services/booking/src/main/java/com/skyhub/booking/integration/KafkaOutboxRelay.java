package com.skyhub.booking.integration;

import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Publishes outbox events to Kafka (ADR-0019). Each poll takes a batch of unpublished rows, sends each one and
 * <em>waits for the broker to acknowledge it</em>, and only then marks it published. A failure stops the batch, so
 * a later event never overtakes an earlier one, and leaves the rest pending for the next poll. A crash between
 * "acknowledged" and "marked" sends that event again, so consumers must tolerate duplicates.
 *
 * <p>The batch holds its row locks while it waits for the broker, which is why batches are small and the wait is
 * bounded. Several booking instances can run this at once: locked rows are skipped, not waited for.
 */
@Component
@ConditionalOnProperty(name = "skyhub.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final PiiCipher cipher;
    private final Clock clock;
    private final TransactionOperations transactions;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final Counter published;
    private final Counter failures;

    public KafkaOutboxRelay(OutboxEventRepository outbox, KafkaTemplate<String, String> kafka, PiiCipher cipher,
                            Clock clock, TransactionOperations transactions, MeterRegistry registry,
                            @Value("${skyhub.outbox.relay.batch-size:50}") int batchSize,
                            @Value("${skyhub.outbox.relay.send-timeout:PT5S}") Duration sendTimeout,
                            @Value("${skyhub.outbox.retention:P7D}") Duration retention) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.cipher = cipher;
        this.clock = clock;
        this.transactions = transactions;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.published = Counter.builder("skyhub.outbox.published").description("Events published to Kafka").register(registry);
        this.failures = Counter.builder("skyhub.outbox.publish.failures")
                .description("Events that could not be published and stay pending").register(registry);
        Gauge.builder("skyhub.outbox.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Events waiting to be published").register(registry);
    }

    @Scheduled(fixedDelayString = "${skyhub.outbox.relay.interval:PT2S}")
    public void publishPending() {
        try {
            transactions.executeWithoutResult(status -> publishBatch());
        } catch (RuntimeException e) {
            // A failed poll must not stop the schedule: the next one tries again.
            log.warn("Outbox poll failed; will retry", e);
        }
    }

    private void publishBatch() {
        List<OutboxEvent> batch = outbox.lockPending(batchSize);
        List<OutboxEvent> done = new ArrayList<>();
        for (OutboxEvent event : batch) {
            try {
                kafka.send(event.getTopic(), event.getAggregateId(), cipher.decrypt(event.getPayload()))
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failures.increment();
                log.atWarn().addKeyValue("outbox.event", event.getId()).setCause(e)
                        .log("Event not published; it stays pending");
                break; // keep order: nothing after this one may go first
            }
            event.markPublished(clock.instant());
            done.add(event);
            published.increment();
        }
        if (!done.isEmpty()) {
            outbox.saveAll(done);
        }
    }

    /** Published rows are only kept for a while: long enough to investigate, short enough not to grow forever. */
    @Scheduled(fixedDelayString = "${skyhub.outbox.purge-interval:PT1H}")
    public void purgePublished() {
        try {
            int removed = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
            if (removed > 0) {
                log.atInfo().addKeyValue("outbox.purged", removed).log("Old published events removed");
            }
        } catch (RuntimeException e) {
            log.warn("Outbox purge failed; will retry", e);
        }
    }
}
