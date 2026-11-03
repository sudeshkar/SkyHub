package com.skyhub.notification.messaging;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What an operator may see about a message on the dead-letter topic (ADR-0032): where it is, when, what kind of event it was
 * and why it was parked. Never its body, which holds the traveller's address, and never an address that an error message
 * happens to contain.
 */
public record ParkedMessage(int partition, long offset, long timestamp, String originalTopic, String eventType, String error,
                            boolean skipped) {

    /** A message that is not (yet) known to be skipped. */
    public ParkedMessage(int partition, long offset, long timestamp, String originalTopic, String eventType, String error) {
        this(partition, offset, timestamp, originalTopic, eventType, error, false);
    }

    /** The same message, marked as one the next replay will leave out (ADR-0037). */
    ParkedMessage withSkipped(boolean skipped) {
        return new ParkedMessage(partition, offset, timestamp, originalTopic, eventType, error, skipped);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ADDRESS = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final int MAX_ERROR = 200;
    private static final String[] ORIGINAL_TOPIC = {"kafka_original-topic", "kafka_dlt-original-topic"};
    private static final String[] EXCEPTION_MESSAGE = {"kafka_exception-message", "kafka_dlt-exception-message"};

    static ParkedMessage of(ConsumerRecord<String, String> record) {
        return new ParkedMessage(record.partition(), record.offset(), record.timestamp(),
                header(record, "unknown", ORIGINAL_TOPIC), typeOf(record.value()),
                clean(header(record, "no reason recorded", EXCEPTION_MESSAGE)));
    }

    /**
     * Spring Kafka 4 writes {@code kafka_original-topic} and {@code kafka_exception-message}; earlier versions (and messages put there by
     * hand) used {@code kafka_dlt-} names. The first name present wins (ADR-0065).
     */
    private static String header(ConsumerRecord<String, String> record, String fallback, String... names) {
        for (String name : names) {
            Header header = record.headers().lastHeader(name);
            if (header != null && header.value() != null) {
                return new String(header.value(), StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }

    private static String typeOf(String value) {
        try {
            JsonNode type = JSON.readTree(value).get("type");
            return type != null && type.isString() ? type.asString() : "unreadable";
        } catch (RuntimeException notJson) {
            return "unreadable";
        }
    }

    private static String clean(String error) {
        String hidden = hideAddresses(error);
        return hidden.length() <= MAX_ERROR ? hidden : hidden.substring(0, MAX_ERROR);
    }

    /** Any email address in free text, replaced: operators' notes and error messages must not become a list of addresses. */
    static String hideAddresses(String text) {
        return ADDRESS.matcher(text).replaceAll("[address hidden]");
    }
}
