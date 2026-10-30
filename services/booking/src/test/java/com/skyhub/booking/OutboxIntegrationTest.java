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

    @Test
    void should_record_a_cancellation_event_saying_the_money_comes_back_when_cancelled_early() throws Exception {
        String locator = bookedLocator("SK777", "2027-08-10", "12A");

        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", manageToken(locator)))
                .andExpect(status().isOk());

        List<String> types = jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY seq", String.class, locator);
        assertThat(types).containsExactly("booking.confirmed", "booking.cancelled");
    }

    @Test
    void should_record_the_cancellation_event_once_however_often_cancel_is_pressed() throws Exception {
        String locator = bookedLocator("SK777", "2027-08-12", "12A");
        String token = manageToken(locator);

        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", locator).header("Authorization", token)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'booking.cancelled'",
                Long.class, locator)).isEqualTo(1);
    }

    @Test
    void should_tell_each_active_booking_on_a_disrupted_flight_but_not_a_cancelled_one_and_not_twice() throws Exception {
        String stays = bookedLocator("SK501", "2027-08-11", "12A");
        String alsoStays = bookedLocator("SK501", "2027-08-11", "12B");
        String leaves = bookedLocator("SK501", "2027-08-11", "12C");
        mvc.perform(post("/api/v1/pnrs/{l}/cancellation", leaves).header("Authorization", manageToken(leaves))).andExpect(status().isOk());
        String admin = TestStaff.adminBearer(mvc);
        String delay = "{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-08-11\",\"type\":\"DELAYED\","
                + "\"revisedDeparture\":\"2027-08-11T14:30:00Z\",\"reason\":\"Late inbound aircraft\"}";

        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(delay))
                .andExpect(status().isOk());
        // the operations tool retrying the same request must not email everybody again
        mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(delay))
                .andExpect(status().isOk());

        assertThat(disruptionEventsFor(stays)).isEqualTo(1);
        assertThat(disruptionEventsFor(alsoStays)).isEqualTo(1);
        assertThat(disruptionEventsFor(leaves)).isZero();
    }

    @Test
    void should_tell_everyone_again_when_the_delay_changes() throws Exception {
        String locator = bookedLocator("SK501", "2027-08-13", "12A");
        String admin = TestStaff.adminBearer(mvc);
        for (String time : List.of("14:30", "16:00")) {
            mvc.perform(post("/api/v1/ops/disruptions").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"flightNumber\":\"SK501\",\"departureDate\":\"2027-08-13\",\"type\":\"DELAYED\","
                                    + "\"revisedDeparture\":\"2027-08-13T%s:00Z\"}".formatted(time)))
                    .andExpect(status().isOk());
        }

        assertThat(disruptionEventsFor(locator)).isEqualTo(2);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private long disruptionEventsFor(String locator) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'booking.disrupted'",
                Long.class, locator);
    }

    private String bookedLocator(String flight, String date, String seat) throws Exception {
        String body = book(flight, date, seat, "tok_visa").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.locator");
    }

    private String manageToken(String locator) throws Exception {
        String body = mvc.perform(post("/api/v1/pnrs/{l}/sessions", locator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Perera\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(body, "$.accessToken");
    }

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
