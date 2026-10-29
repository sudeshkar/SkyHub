package com.skyhub.booking.application;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * SHA-256 of a booking request's meaningful content. Stored with the hashed Idempotency-Key so a key
 * can only replay the request it was first used for.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(CreatePnrCommand command) {
        return Sha256.hex(String.join("|",
                command.contactEmail().strip().toLowerCase(Locale.ROOT),
                command.segments().stream()
                        .map(s -> s.flightNumber() + "@" + s.departureDate())
                        .collect(Collectors.joining(",")),
                command.passengers().stream()
                        .map(p -> p.firstName().strip() + "/" + p.lastName().strip() + ":" + String.join(",", p.seats()))
                        .collect(Collectors.joining(";"))));
    }
}
