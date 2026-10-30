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
public record ParkedMessage(int partition, long offset, long timestamp, String originalTopic, String eventType, String error) {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ADDRESS = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final int MAX_ERROR = 200;

    static ParkedMessage of(ConsumerRecord<String, String> record) {
        return new ParkedMessage(record.partition(), record.offset(), record.timestamp(),
                header(record, "kafka_dlt-original-topic", "unknown"), typeOf(record.value()),
                clean(header(record, "kafka_dlt-exception-message", "no reason recorded")));
    }

    private static String header(ConsumerRecord<String, String> record, String name, String fallback) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? fallback : new String(header.value(), StandardCharsets.UTF_8);
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
        String hidden = ADDRESS.matcher(error).replaceAll("[address hidden]");
        return hidden.length() <= MAX_ERROR ? hidden : hidden.substring(0, MAX_ERROR);
    }
}
