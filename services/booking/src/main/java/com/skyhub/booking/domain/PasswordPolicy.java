package com.skyhub.booking.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The one rule for what a password may be, for staff and travellers alike (ADR-0048): at least 12 characters, and at most 72 bytes in
 * UTF-8. The upper limit is BCrypt's, not a choice: it reads only the first 72 bytes and the encoder in use refuses anything longer,
 * so a password over it cannot be stored. It is counted in bytes because that is how BCrypt counts: 50 accented letters are 100 bytes.
 * The lower limit is counted in characters (code points), because it is about what a person typed.
 *
 * <p>The password is judged exactly as given: nothing is trimmed, and spaces count. The message never contains the password.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_BYTES = 72;

    /** One message for every violation, so it always states the whole rule. */
    static final String MESSAGE = "A password needs " + MIN_CHARACTERS + " to " + MAX_BYTES
            + " characters, counting accents and symbols as more than one (" + MAX_BYTES + " bytes at most)";

    private PasswordPolicy() {
    }

    /** @return why the password is not acceptable, or empty if it is */
    public static Optional<String> violation(String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_CHARACTERS) {
            return Optional.of(MESSAGE);
        }
        // Every UTF-16 unit takes at least one byte, so a long string is over the limit without encoding all of it.
        if (password.length() > MAX_BYTES || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of(MESSAGE);
        }
        return Optional.empty();
    }
}
