package com.skyhub.notification.messaging;

import java.util.regex.Pattern;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;

/**
 * Who is running a management-port action (ADR-0052): replay, skip or take a skip off. The port has no login (ADR-0032), so this is
 * the person's own word, not proof, and it is recorded as that. It is limited to letters, digits, spaces and {@code . _ - @ +} so a
 * name can never forge or split a log line, whatever it is used in.
 */
public record Operator(String name) {

    static final int MAX_LENGTH = 60;

    private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} ._@+-]*$");

    /** @throws InvalidEndpointRequestException (a 400) when there is none, or it is not a plain name */
    public static Operator named(String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_LENGTH || !ALLOWED.matcher(cleaned).matches()) {
            String message = "Say who is running this: an operator of 1 to " + MAX_LENGTH
                    + " letters, digits, spaces or . _ - @ +";
            throw new InvalidEndpointRequestException(message, message);
        }
        return new Operator(cleaned);
    }

    @Override
    public String toString() {
        return name;
    }
}
