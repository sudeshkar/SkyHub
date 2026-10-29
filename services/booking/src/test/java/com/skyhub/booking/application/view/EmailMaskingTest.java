package com.skyhub.booking.application.view;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmailMaskingTest {

    @ParameterizedTest
    @CsvSource({
            "priya@example.com, p****@example.com",
            "a@example.com, ****@example.com",
            "not-an-email, ****"
    })
    void should_mask_local_part(String email, String expected) {
        assertThat(EmailMasking.mask(email)).isEqualTo(expected);
    }
}
