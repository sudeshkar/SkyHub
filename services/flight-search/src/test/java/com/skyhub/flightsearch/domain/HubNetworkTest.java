package com.skyhub.flightsearch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class HubNetworkTest {

    private static final Hub DXB = new Hub("DXB", "Dubai International", true, Duration.ofMinutes(75));
    private static final Hub DWC = new Hub("DWC", "Al Maktoum International", true, Duration.ofMinutes(60));
    private static final AirportTransfer DXB_DWC = new AirportTransfer("DXB", "DWC", Duration.ofMinutes(120));

    private final HubNetwork network = new HubNetwork(List.of(DXB, DWC), List.of(DXB_DWC), Duration.ofHours(12));

    @Nested
    class RequiredConnectionTime {

        @Test
        void should_require_hub_mct_when_arriving_and_departing_at_same_airport() {
            assertThat(network.requiredConnectionTime("DXB", "DXB")).isEqualTo(Duration.ofMinutes(75));
        }

        @Test
        void should_require_transfer_plus_departure_hub_mct_when_changing_airports() {
            assertThat(network.requiredConnectionTime("DXB", "DWC")).isEqualTo(Duration.ofMinutes(180));
        }

        @Test
        void should_use_same_transfer_time_in_both_directions() {
            assertThat(network.transferTime("DWC", "DXB")).isEqualTo(Duration.ofMinutes(120));
            assertThat(network.requiredConnectionTime("DWC", "DXB")).isEqualTo(Duration.ofMinutes(195));
        }

        @Test
        void should_have_no_transfer_time_within_one_airport() {
            assertThat(network.transferTime("DWC", "DWC")).isZero();
        }

        @Test
        void should_reject_airports_without_ground_transfer() {
            var single = new HubNetwork(List.of(DXB), List.of(), Duration.ofHours(12));

            assertThatIllegalArgumentException().isThrownBy(() -> single.transferTime("DXB", "LHR"));
        }
    }

    @Nested
    class OnwardAirports {

        @Test
        void should_include_partner_airport_when_active() {
            assertThat(network.onwardAirports("DXB")).containsExactly("DXB", "DWC");
        }

        @Test
        void should_exclude_inactive_partner_hub() {
            var dwcClosed = new HubNetwork(
                    List.of(DXB, new Hub("DWC", "Al Maktoum International", false, Duration.ofMinutes(60))),
                    List.of(DXB_DWC),
                    Duration.ofHours(12));

            assertThat(dwcClosed.onwardAirports("DXB")).containsExactly("DXB");
        }

        @Test
        void should_be_empty_when_arrival_airport_is_not_an_active_hub() {
            assertThat(network.onwardAirports("LHR")).isEmpty();
        }
    }

    @Test
    void should_not_treat_unknown_airport_as_hub() {
        assertThat(network.isActiveHub("LHR")).isFalse();
        assertThat(network.isActiveHub("DXB")).isTrue();
    }

    @Nested
    class Validation {

        @Test
        void should_reject_invalid_hub_code() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Hub("DXBB", "Typo", true, Duration.ofMinutes(60)))
                    .withMessageContaining("DXBB");
        }

        @Test
        void should_reject_non_positive_minimum_connection_time() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Hub("DXB", "Dubai", true, Duration.ZERO));
        }

        @Test
        void should_reject_duplicate_hubs() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HubNetwork(List.of(DXB, DXB), List.of(), Duration.ofHours(12)))
                    .withMessageContaining("Duplicate hub DXB");
        }

        @Test
        void should_reject_transfer_to_unknown_hub() {
            var toNowhere = new AirportTransfer("DXB", "SHJ", Duration.ofMinutes(60));

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HubNetwork(List.of(DXB), List.of(toNowhere), Duration.ofHours(12)))
                    .withMessageContaining("SHJ");
        }

        @Test
        void should_reject_transfer_from_airport_to_itself() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AirportTransfer("DXB", "DXB", Duration.ofMinutes(60)));
        }
    }
}
