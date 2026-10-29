package com.skyhub.booking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

class HmacBoardingPassSignerTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private final HmacBoardingPassSigner signer = new HmacBoardingPassSigner(KEY);

    @Test
    void should_sign_so_the_same_key_can_verify() {
        String signed = signer.sign("M1PERERA/PRIYA ABC234 SK501 11D 0001");

        assertThat(signed).startsWith("M1PERERA/PRIYA ABC234 SK501 11D 0001.");
        assertThat(signer.verify(signed)).isTrue();
    }

    @Test
    void should_sign_the_same_payload_the_same_way_each_time() {
        assertThat(signer.sign("payload")).isEqualTo(signer.sign("payload"));
    }

    @Test
    void should_reject_a_pass_whose_seat_was_edited() {
        String signed = signer.sign("M1PERERA/PRIYA ABC234 SK501 11D 0001");

        assertThat(signer.verify(signed.replace("11D", "1A"))).isFalse();
    }

    @Test
    void should_reject_a_pass_signed_with_another_key() {
        String forged = new HmacBoardingPassSigner("another-key-another-key-another!!".getBytes()).sign("payload");

        assertThat(signer.verify(forged)).isFalse();
    }

    @Test
    void should_reject_garbage_without_throwing() {
        assertThat(signer.verify("no signature here")).isFalse();
        assertThat(signer.verify("payload.")).isFalse();
        assertThat(signer.verify("payload.!!!not base64!!!")).isFalse();
        assertThat(signer.verify("")).isFalse();
    }

    @Test
    void should_refuse_a_key_that_is_too_short() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new HmacBoardingPassSigner(new byte[8]));
    }
}
