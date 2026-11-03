package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which boarding window applies to a flight: the standard one, or the one set for its route (ADR-0077). */
class BoardingWindowsTest {

    private static final BoardingWindow STANDARD = new BoardingWindow(Duration.ofMinutes(45), Duration.ofMinutes(10));
    private static final BoardingWindow LONG_HAUL = new BoardingWindow(Duration.ofMinutes(75), Duration.ofMinutes(20));
    private final BoardingWindows windows = new BoardingWindows(STANDARD, Map.of("DXB-JFK", LONG_HAUL));

    @Test
    void should_use_the_window_set_for_the_route_and_the_standard_one_for_every_other() {
        assertThat(windows.forRoute("DXB", "JFK")).isEqualTo(LONG_HAUL);
        assertThat(windows.forRoute("DXB", "LHR")).isEqualTo(STANDARD);
    }

    @Test
    void should_treat_a_route_as_having_a_direction_so_the_flight_back_is_not_the_same_route() {
        assertThat(windows.forRoute("JFK", "DXB")).isEqualTo(STANDARD);
    }

    @Test
    void should_not_mind_the_case_of_the_airport_codes() {
        assertThat(windows.forRoute("dxb", "jfk")).isEqualTo(LONG_HAUL);
    }

    @Test
    void should_use_the_standard_window_when_the_route_is_not_known() {
        assertThat(windows.forRoute(null, "JFK")).isEqualTo(STANDARD);
        assertThat(windows.forRoute("DXB", null)).isEqualTo(STANDARD);
        assertThat(windows.forRoute(null, null)).isEqualTo(STANDARD);
    }

    @Test
    void should_read_route_keys_in_any_case_and_keep_the_standard_window_available() {
        BoardingWindows lower = new BoardingWindows(STANDARD, Map.of("dxb-jfk", LONG_HAUL));

        assertThat(lower.forRoute("DXB", "JFK")).isEqualTo(LONG_HAUL);
        assertThat(lower.standard()).isEqualTo(STANDARD);
    }

    @Test
    void should_refuse_a_route_key_that_is_not_two_airport_codes_naming_it() {
        for (String key : new String[] {"DXB", "DXBJFK", "DXB-JFK-LHR", "DX-JFK", "DXB-J1K", "", " DXB-JFK"}) {
            assertThatIllegalArgumentException().as(key).isThrownBy(() -> new BoardingWindows(STANDARD, Map.of(key, LONG_HAUL)))
                    .withMessageContaining("route");
        }
    }

    @Test
    void should_have_no_overrides_when_none_are_given() {
        assertThat(new BoardingWindows(STANDARD, Map.of()).forRoute("DXB", "JFK")).isEqualTo(STANDARD);
        assertThat(new BoardingWindows(STANDARD, null).forRoute("DXB", "JFK")).isEqualTo(STANDARD);
    }
}
