package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class RandomLocatorGeneratorTest {

    private final RandomLocatorGenerator generator = new RandomLocatorGenerator();

    @RepeatedTest(50)
    void should_generate_six_unambiguous_characters() {
        assertThat(generator.next()).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$");
    }

    @Test
    void should_not_repeat_in_practice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.next());
        }
        assertThat(seen).hasSizeGreaterThan(9_990);
    }
}
