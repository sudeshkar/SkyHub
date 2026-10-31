package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The one rule for what a password may be, for staff and travellers alike (ADR-0048). */
class PasswordPolicyTest {

    @Test
    void should_accept_twelve_characters_and_up_to_seventy_two_bytes() {
        assertThat(PasswordPolicy.violation("a".repeat(12))).isEmpty();
        assertThat(PasswordPolicy.violation("a".repeat(72))).isEmpty();
        assertThat(PasswordPolicy.violation("correct horse battery staple")).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "short", "elevenchars"})
    void should_refuse_a_password_that_is_missing_or_under_twelve_characters(String tooShort) {
        assertThat(PasswordPolicy.violation(tooShort)).hasValueSatisfying(message -> assertThat(message).contains("12"));
    }

    @Test
    void should_refuse_seventy_three_bytes_which_the_hashing_cannot_store() {
        assertThat(PasswordPolicy.violation("a".repeat(73))).hasValueSatisfying(message -> assertThat(message).contains("72"));
        assertThat(PasswordPolicy.violation("a".repeat(100))).isPresent();
        assertThat(PasswordPolicy.violation("a".repeat(5_000))).isPresent();
    }

    @Test
    void should_count_bytes_not_characters_for_the_upper_limit() {
        // 36 accented letters are 72 bytes: fine. 37 are 74 bytes, though far fewer than 72 characters: refused.
        assertThat(PasswordPolicy.violation("é".repeat(36))).isEmpty();
        assertThat(PasswordPolicy.violation("é".repeat(37))).hasValueSatisfying(message -> assertThat(message).contains("72"));
        // 24 letters of a script that takes three bytes each are exactly 72 bytes; one more is over.
        assertThat(PasswordPolicy.violation("あ".repeat(24))).isEmpty();
        assertThat(PasswordPolicy.violation("あ".repeat(25))).isPresent();
    }

    @Test
    void should_count_characters_not_bytes_for_the_lower_limit() {
        // Twelve accented letters are 24 bytes but twelve characters; eleven are not enough however many bytes they take.
        assertThat(PasswordPolicy.violation("é".repeat(12))).isEmpty();
        assertThat(PasswordPolicy.violation("é".repeat(11))).isPresent();
    }

    @Test
    void should_count_a_symbol_made_of_two_utf16_units_as_one_character() {
        // 12 of them are 48 bytes: long enough, because a person sees twelve symbols, not twenty-four.
        assertThat(PasswordPolicy.violation("😀".repeat(12))).isEmpty();
        assertThat(PasswordPolicy.violation("😀".repeat(11))).isPresent();
        assertThat(PasswordPolicy.violation("😀".repeat(18))).isEmpty();     // 72 bytes
        assertThat(PasswordPolicy.violation("😀".repeat(19))).isPresent();   // 76 bytes
    }

    @Test
    void should_not_trim_or_otherwise_change_what_it_is_given() {
        // The password is exactly what was typed: eleven letters and a space are twelve characters, and spaces count.
        assertThat(PasswordPolicy.violation("elevenchars ")).isEmpty();
        assertThat(PasswordPolicy.violation(" ".repeat(12))).isEmpty();
    }

    @Test
    void should_give_one_message_that_states_the_whole_rule_for_every_violation() {
        String tooShort = PasswordPolicy.violation("short").orElseThrow();
        String tooLong = PasswordPolicy.violation("a".repeat(80)).orElseThrow();

        assertThat(tooShort).isEqualTo(tooLong).contains("12").contains("72").contains("bytes");
    }

    @Test
    void should_never_repeat_the_password_in_its_message() {
        assertThat(PasswordPolicy.violation("secret-but-short")).isEmpty();
        assertThat(PasswordPolicy.violation("tiny secret").orElseThrow()).doesNotContain("tiny secret");
        assertThat(PasswordPolicy.violation("x".repeat(90) + "UNIQUE").orElseThrow()).doesNotContain("UNIQUE");
    }
}
