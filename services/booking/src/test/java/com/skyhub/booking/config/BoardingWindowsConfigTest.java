package com.skyhub.booking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.skyhub.booking.domain.BoardingWindow;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The boarding windows as an operator sets them (ADR-0077): a standard one and, if wanted, one for particular routes. */
class BoardingWindowsConfigTest {

    private static Binder binderOf(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties));
    }

    @Test
    void should_default_to_boarding_that_opens_45_minutes_and_closes_10_minutes_before_departure_with_no_route_overrides() {
        var windows = CheckInConfig.boardingWindows(binderOf(Map.of()));

        assertThat(windows.standard()).isEqualTo(new BoardingWindow(Duration.ofMinutes(45), Duration.ofMinutes(10)));
        assertThat(windows.forRoute("DXB", "JFK")).isEqualTo(windows.standard());
    }

    @Test
    void should_take_the_standard_window_from_its_own_settings() {
        var windows = CheckInConfig.boardingWindows(binderOf(Map.of(
                "skyhub.booking.boarding.opens-before", "PT50M", "skyhub.booking.boarding.closes-before", "PT12M")));

        assertThat(windows.standard()).isEqualTo(new BoardingWindow(Duration.ofMinutes(50), Duration.ofMinutes(12)));
    }

    @Test
    void should_read_a_window_for_a_route() {
        var windows = CheckInConfig.boardingWindows(binderOf(Map.of(
                "skyhub.booking.boarding.routes.DXB-JFK.opens-before", "PT75M", "skyhub.booking.boarding.routes.DXB-JFK.closes-before", "PT20M")));

        assertThat(windows.forRoute("DXB", "JFK")).isEqualTo(new BoardingWindow(Duration.ofMinutes(75), Duration.ofMinutes(20)));
        assertThat(windows.forRoute("DXB", "LHR")).isEqualTo(windows.standard());
    }

    @Test
    void should_let_a_route_change_only_one_end_and_keep_the_standard_for_the_other() {
        var windows = CheckInConfig.boardingWindows(binderOf(Map.of("skyhub.booking.boarding.routes.DXB-JFK.opens-before", "PT90M")));

        assertThat(windows.forRoute("DXB", "JFK")).isEqualTo(new BoardingWindow(Duration.ofMinutes(90), Duration.ofMinutes(10)));
    }

    @Test
    void should_stop_the_service_starting_with_the_route_named_when_its_window_cannot_work() {
        // Closing at or before the opening, or after departure, is a mistake that would silently keep a flight from ever boarding.
        assertThatIllegalStateException().isThrownBy(() -> CheckInConfig.boardingWindows(binderOf(Map.of(
                "skyhub.booking.boarding.routes.DXB-JFK.opens-before", "PT10M", "skyhub.booking.boarding.routes.DXB-JFK.closes-before", "PT20M"))))
                .withMessageContaining("DXB-JFK");
    }

    @Test
    void should_stop_the_service_starting_when_a_route_is_not_two_airport_codes() {
        assertThatIllegalStateException().isThrownBy(() -> CheckInConfig.boardingWindows(binderOf(Map.of(
                "skyhub.booking.boarding.routes.DUBAI.opens-before", "PT60M"))))
                .withMessageContaining("DUBAI");
    }
}
