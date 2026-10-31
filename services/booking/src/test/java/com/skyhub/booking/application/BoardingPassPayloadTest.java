package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The text a gate scans, before it is signed (ADR-0013), and reading it back (ADR-0056). */
class BoardingPassPayloadTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    @Test
    void should_write_the_payload_the_way_check_in_always_has() {
        assertThat(BoardingPassPayload.of("PERERA/PRIYA", "ABC234", "SK501", DATE, "11D", 7))
                .isEqualTo("M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007");
    }

    @Test
    void should_read_back_what_it_wrote() {
        var parsed = BoardingPassPayload.parse(BoardingPassPayload.of("PERERA/PRIYA", "ABC234", "SK501", DATE, "11D", 7) + ".signature").orElseThrow();

        assertThat(parsed.name()).isEqualTo("PERERA/PRIYA");
        assertThat(parsed.locator()).isEqualTo("ABC234");
        assertThat(parsed.flightNumber()).isEqualTo("SK501");
        assertThat(parsed.departureDate()).isEqualTo(DATE);
        assertThat(parsed.seat()).isEqualTo("11D");
        assertThat(parsed.sequence()).isEqualTo(7);
        assertThat(parsed.payload()).isEqualTo("M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007");
    }

    @Test
    void should_read_a_name_that_has_spaces_in_it() {
        var parsed = BoardingPassPayload.parse(BoardingPassPayload.of("DE SILVA/ANNA MARIA", "ABC234", "SK501", DATE, "11D", 12) + ".sig").orElseThrow();

        assertThat(parsed.name()).isEqualTo("DE SILVA/ANNA MARIA");
        assertThat(parsed.sequence()).isEqualTo(12);
    }

    @Test
    void should_read_nothing_from_text_that_is_not_a_pass() {
        for (String junk : new String[] {"", "hello", "M1ONLY.sig", "no-dot-at-all", "M1X ABC234 SK501 not-a-date 11D 0007.sig",
                "M1X ABC234 SK501 2026-11-02 11D seven.sig", "X1X ABC234 SK501 2026-11-02 11D 0007.sig", "M1X abc234 SK501 2026-11-02 11D 0007.sig",
                "M1X ABC234 SK501 2026-11-02 11D -1.sig"}) {
            assertThat(BoardingPassPayload.parse(junk)).as(junk).isEmpty();
        }
    }
}
