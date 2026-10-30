package com.skyhub.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.skyhub.notification.application.EmailMessage;
import com.skyhub.notification.application.EmailUnavailableException;
import com.skyhub.notification.integration.LoggingEmailSender;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The whole consumer (ADR-0019) on real PostgreSQL and a real in-process Kafka broker: an event on the topic
 * becomes exactly one recorded email, however many times it is delivered, and a message that can never be handled
 * ends up on the dead-letter topic instead of blocking everything behind it.
 */
@SpringBootTest(properties = {"skyhub.notifications.retry-interval=PT0.2S", "skyhub.notifications.retry-attempts=3"})
@EmbeddedKafka(topics = {"skyhub.booking.events", "skyhub.booking.events.DLT"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(PostgresTestcontainer.class)
class NotificationIntegrationTest {

    private static final Path EXAMPLE = Path.of("../../api-specs/events/booking-confirmed.v1.example.json");
    private static final String TOPIC = "skyhub.booking.events";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private LoggingEmailSender email;

    @Autowired
    private MeterRegistry registry;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /** The shared example, with its own event id and booking reference so tests do not see each other's rows. */
    private static String event(UUID id, String locator) throws Exception {
        return Files.readString(EXAMPLE)
                .replace("0b8f6f5e-3c47-4a58-9d0e-6a4f7e2f1c11", id.toString())
                .replace("ABC234", locator);
    }

    private long rows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM sent_notification WHERE event_id = ?", Long.class, id);
    }

    @Test
    void should_record_one_email_for_a_booking_confirmed_event() throws Exception {
        UUID id = UUID.randomUUID();

        kafka.send(TOPIC, "AAA111", event(id, "AAA111"));

        await().atMost(Duration.ofSeconds(30)).until(() -> rows(id) == 1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT locator, recipient, subject FROM sent_notification WHERE event_id = ?", id);
        assertThat(row.get("locator")).isEqualTo("AAA111");
        assertThat(row.get("recipient")).isEqualTo("p****@example.com");
        assertThat((String) row.get("subject")).contains("AAA111");
    }

    @Test
    void should_send_one_email_however_many_times_the_event_is_delivered() throws Exception {
        UUID id = UUID.randomUUID();
        String message = event(id, "BBB222");

        kafka.send(TOPIC, "BBB222", message);
        kafka.send(TOPIC, "BBB222", message);
        kafka.send(TOPIC, "BBB222", message);
        // a later, different event proves the earlier duplicates have been processed too
        UUID marker = UUID.randomUUID();
        kafka.send(TOPIC, "BBB222", event(marker, "MARKER"));

        await().atMost(Duration.ofSeconds(30)).until(() -> rows(marker) == 1);
        assertThat(rows(id)).isEqualTo(1);
        verify(email, times(1)).send(org.mockito.ArgumentMatchers.argThat(m -> m.subject().contains("BBB222")));
    }

    @Test
    void should_retry_when_the_mail_server_fails_and_still_send_only_once() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new EmailUnavailableException("smtp down", null)).doCallRealMethod().when(email).send(any(EmailMessage.class));

        kafka.send(TOPIC, "CCC333", event(id, "CCC333"));

        await().atMost(Duration.ofSeconds(30)).until(() -> rows(id) == 1);
        assertThat(rows(id)).isEqualTo(1);
    }

    @Test
    void should_park_a_message_it_can_never_handle_on_the_dead_letter_topic_and_carry_on() throws Exception {
        String poison = "{\"type\":\"booking.confirmed\",\"note\":\"" + UUID.randomUUID() + "\"}";
        UUID after = UUID.randomUUID();

        kafka.send(TOPIC, "DDD444", poison);
        kafka.send(TOPIC, "DDD444", event(after, "EEE555"));

        await().atMost(Duration.ofSeconds(30)).until(() -> rows(after) == 1); // not blocked behind the bad message
        assertThat(deadLetters()).anyMatch(r -> r.value().equals(poison));
        assertThat(registry.get("skyhub.notifications.dead_lettered").counter().count()).isGreaterThanOrEqualTo(1);
    }

    private List<ConsumerRecord<String, String>> deadLetters() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(found::add);
                return !found.isEmpty();
            });
        }
        return found;
    }
}
