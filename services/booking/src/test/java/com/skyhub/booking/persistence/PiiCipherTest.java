package com.skyhub.booking.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PiiCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final PiiCipher cipher = new PiiCipher(KEY);

    @Test
    void should_round_trip() {
        assertThat(cipher.decrypt(cipher.encrypt("priya@example.com"))).isEqualTo("priya@example.com");
    }

    @Test
    void should_not_store_plaintext() {
        assertThat(cipher.encrypt("priya@example.com")).startsWith("v1:").doesNotContain("priya");
    }

    @Test
    void should_use_a_fresh_iv_every_time() {
        assertThat(cipher.encrypt("priya@example.com")).isNotEqualTo(cipher.encrypt("priya@example.com"));
    }

    @Test
    void should_detect_tampering() {
        String sealed = cipher.encrypt("priya@example.com");
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(tampered));
    }

    @Test
    void should_reject_a_value_sealed_with_another_key() {
        byte[] other = new byte[32];
        other[0] = 1;
        String sealed = new PiiCipher(Base64.getEncoder().encodeToString(other)).encrypt("priya@example.com");

        assertThatIllegalStateException().isThrownBy(() -> cipher.decrypt(sealed));
    }

    @Test
    void should_read_legacy_plaintext_rows() {
        assertThat(cipher.decrypt("priya@example.com")).isEqualTo("priya@example.com");
    }

    @Test
    void should_refuse_to_start_without_a_proper_key() {
        assertThatIllegalStateException().isThrownBy(() -> new PiiCipher(""));
        assertThatIllegalStateException().isThrownBy(() -> new PiiCipher("not base64!"));
    }

    @Test
    void should_give_the_same_index_for_the_same_address_whatever_its_case_or_spacing() {
        assertThat(cipher.blindIndex("Priya@Example.com ")).isEqualTo(cipher.blindIndex("priya@example.com"));
    }

    @Test
    void should_give_different_indexes_for_different_addresses_and_reveal_nothing_of_the_address() {
        String index = cipher.blindIndex("priya@example.com");

        assertThat(index).isNotEqualTo(cipher.blindIndex("priyb@example.com")).hasSize(64).doesNotContain("priya");
    }

    @Test
    void should_give_a_different_index_under_a_different_key() {
        PiiCipher other = new PiiCipher("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");

        assertThat(other.blindIndex("priya@example.com")).isNotEqualTo(cipher.blindIndex("priya@example.com"));
    }
}
