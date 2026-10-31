package com.skyhub.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;

class OperatorTest {

    @Test
    void should_accept_a_name_a_username_or_an_address_and_trim_it() {
        assertThat(Operator.named("  priya.perera  ").name()).isEqualTo("priya.perera");
        assertThat(Operator.named("Priya Perera").name()).isEqualTo("Priya Perera");
        assertThat(Operator.named("on-call_2@skyhub.example").name()).isEqualTo("on-call_2@skyhub.example");
    }

    @Test
    void should_require_one() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThatExceptionOfType(InvalidEndpointRequestException.class).as("[" + missing + "]")
                    .isThrownBy(() -> Operator.named(missing)).withMessageContaining("operator");
        }
    }

    @Test
    void should_keep_only_the_trimmed_name_so_a_trailing_line_break_cannot_reach_a_log() {
        assertThat(Operator.named("priya\r\n").name()).isEqualTo("priya");
    }

    @Test
    void should_refuse_one_that_is_too_long() {
        assertThat(Operator.named("x".repeat(60)).name()).hasSize(60);
        assertThatExceptionOfType(InvalidEndpointRequestException.class).isThrownBy(() -> Operator.named("x".repeat(61)));
    }

    @Test
    void should_refuse_anything_that_could_forge_or_split_a_log_line() {
        for (String forged : new String[] {"priya\nlevel=ERROR", "tab\there", "quote\"d", "{json}", "a;b", "<b>", "\u0000", "priya‮evil"}) {
            assertThatExceptionOfType(InvalidEndpointRequestException.class).as(forged).isThrownBy(() -> Operator.named(forged));
        }
    }
}
