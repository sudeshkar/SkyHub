package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class ParkedMessageTest {

    private static ConsumerRecord<String, String> record(String value, String exception) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("kafka_dlt-exception-message", exception.getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_dlt-original-topic", "skyhub.booking.events".getBytes(StandardCharsets.UTF_8));
        return new ConsumerRecord<>("skyhub.booking.events.DLT", 2, 17L, 1_700_000_000_000L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "ABC234", value, headers, java.util.Optional.empty());
    }

    /** The headers Spring Kafka 4 writes when it parks a message: no "dlt" in their names (ADR-0065). */
    private static ConsumerRecord<String, String> parkedBySpringKafka4(String value, String exception) {
        RecordHeaders headers = new RecordHeaders();
        headers.add("kafka_exception-message", exception.getBytes(StandardCharsets.UTF_8));
        headers.add("kafka_original-topic", "skyhub.booking.events".getBytes(StandardCharsets.UTF_8));
        return new ConsumerRecord<>("skyhub.booking.events.DLT", 1, 5L, 1_700_000_000_000L,
                org.apache.kafka.common.record.TimestampType.CREATE_TIME, 0, 0, "ABC234", value, headers, java.util.Optional.empty());
    }

    @Test
    void should_read_the_reason_and_the_original_topic_from_the_headers_the_library_really_writes() {
        ParkedMessage parked = ParkedMessage.of(parkedBySpringKafka4("{\"type\":\"booking.confirmed\"}", "Mail server did not answer"));

        assertThat(parked.originalTopic()).isEqualTo("skyhub.booking.events");
        assertThat(parked.error()).isEqualTo("Mail server did not answer");
    }

    @Test
    void should_still_read_the_older_header_names_and_say_so_when_there_are_none() {
        assertThat(ParkedMessage.of(record("{\"type\":\"booking.confirmed\"}", "Old style")).error()).isEqualTo("Old style");

        ConsumerRecord<String, String> bare = new ConsumerRecord<>("skyhub.booking.events.DLT", 0, 1L, "ABC234", "{}");
        assertThat(ParkedMessage.of(bare).error()).isEqualTo("no reason recorded");
        assertThat(ParkedMessage.of(bare).originalTopic()).isEqualTo("unknown");
    }

    @Test
    void should_describe_where_the_message_is_why_it_was_parked_and_what_kind_of_event_it_was() {
        ParkedMessage parked = ParkedMessage.of(record("{\"type\":\"booking.confirmed\",\"contactEmail\":\"priya@example.com\"}",
                "Mail server did not answer"));

        assertThat(parked.partition()).isEqualTo(2);
        assertThat(parked.offset()).isEqualTo(17L);
        assertThat(parked.originalTopic()).isEqualTo("skyhub.booking.events");
        assertThat(parked.eventType()).isEqualTo("booking.confirmed");
        assertThat(parked.error()).isEqualTo("Mail server did not answer");
    }

    @Test
    void should_never_show_the_message_body_or_an_email_address_found_in_the_error() {
        ParkedMessage parked = ParkedMessage.of(record("{\"type\":\"booking.confirmed\",\"contactEmail\":\"priya@example.com\"}",
                "Invalid Addresses: priya@example.com rejected"));

        assertThat(parked.toString()).doesNotContain("priya@example.com").doesNotContain("contactEmail");
        assertThat(parked.error()).isEqualTo("Invalid Addresses: [address hidden] rejected");
    }

    @Test
    void should_cope_with_a_message_that_is_not_json_and_a_very_long_error() {
        ParkedMessage parked = ParkedMessage.of(record("this is not json", "x".repeat(2000)));

        assertThat(parked.eventType()).isEqualTo("unreadable");
        assertThat(parked.error()).hasSizeLessThanOrEqualTo(200);
    }
}
