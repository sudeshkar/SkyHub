package com.skyhub.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * SHA-256 of a booking request's meaningful content. Stored with the Idempotency-Key so a key can only
 * replay the request it was first used for.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(CreatePnrCommand command) {
        String canonical = String.join("|",
                command.contactEmail().strip().toLowerCase(Locale.ROOT),
                command.segments().stream()
                        .map(s -> s.flightNumber() + "@" + s.departureDate())
                        .collect(Collectors.joining(",")),
                command.passengers().stream()
                        .map(p -> p.firstName().strip() + "/" + p.lastName().strip() + ":" + String.join(",", p.seats()))
                        .collect(Collectors.joining(";")));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
