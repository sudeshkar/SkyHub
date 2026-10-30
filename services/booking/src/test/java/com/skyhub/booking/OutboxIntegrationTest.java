package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The outbox (ADR-0019) on real PostgreSQL and a real (in-process) Kafka broker, through the real filter chain, with the relay
 * switched on. The event commits with the booking or not at all, and what reaches the broker is readable by the
 * consumer while what sits in the table is not.
 */
@SpringBootTest(properties = "skyhub.outbox.relay.enabled=true")
@EmbeddedKafka(topics = "skyhub.booking.events", bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class OutboxIntegrationTest {

    private static final String TOPIC = "skyhub.booking.events";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void should_commit_the_event_with_the_booking_and_keep_the_email_unreadable_in_the_table() throws Exception {
        String created = book("SK501", "2027-08-01", "12A", "tok_visa").andReturn().getResponse().getContentAsString();
        String locator = JsonPath.read(created, "$.locator");

        Map<String, Object> row = jdbc.queryForMap("SELECT event_type, aggregate_id, payload FROM outbox_event WHERE aggregate_id = ?", locator);

        assertThat(row.get("event_type")).isEqualTo("booking.confirmed");
        assertThat((String) row.get("payload")).startsWith("v1:").doesNotContain("priya@example.com").doesNotContain("Perera");
    }

    @Test
    void should_deliver_the_event_to_kafka_readable_and_mark_it_published() throws Exception {
        String body = book("SK501", "2027-08-02", "12A", "tok_visa").andReturn().getResponse().getContentAsString();
        String locator = JsonPath.read(body, "$.locator");

        ConsumerRecord<String, String> record = awaitMessageFor(locator);

        assertThat(record.key()).isEqualTo(locator);
        assertThat(JsonPath.<String>read(record.value(), "$.type")).isEqualTo("booking.confirmed");
        assertThat(JsonPath.<String>read(record.value(), "$.contactEmail")).isEqualTo("priya@example.com");
        assertThat(JsonPath.<Double>read(record.value(), "$.totalAmount")).isEqualTo(100.00);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_event WHERE aggregate_id = ?", Boolean.class, locator)).isTrue());
    }

    @Test
    void should_leave_no_event_when_the_payment_is_declined() throws Exception {
        long before = events();

        book("SK501", "2027-08-03", "12A", "tok_declined").andExpect(status().isPaymentRequired());

        assertThat(events()).isEqualTo(before);
    }

    @Test
    void should_leave_no_event_for_a_booking_that_rolled_back_on_a_taken_seat() throws Exception {
        book("SK501", "2027-08-04", "12A", "tok_visa").andExpect(status().isCreated());
        long afterFirst = events();

        book("SK501", "2027-08-04", "12A", "tok_visa").andExpect(status().isConflict());

        assertThat(events()).isEqualTo(afterFirst);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long events() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class);
    }

    private org.springframework.test.web.servlet.ResultActions book(String flight, String date, String seat, String token)
            throws Exception {
        String body = """
                {
                  "paymentToken": "%s",
                  "contactEmail": "priya@example.com",
                  "segments": [{"flightNumber": "%s", "departureDate": "%s"}],
                  "passengers": [{"firstName": "Priya", "lastName": "Perera", "seats": ["%s"]}]
                }
                """.formatted(token, flight, date, seat);
        return mvc.perform(post("/api/v1/pnrs").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Reads the topic from the start until the message for this booking arrives. */
    private ConsumerRecord<String, String> awaitMessageFor(String locator) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(TOPIC));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (locator.equals(r.key())) found.add(r);
                });
                return !found.isEmpty();
            });
        }
        return found.get(0);
    }
}
