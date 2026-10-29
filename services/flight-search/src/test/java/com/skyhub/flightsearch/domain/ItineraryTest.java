package com.skyhub.flightsearch.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ItineraryTest {

    private static final FlightLeg CMB_DXB = leg("SK501", "CMB", "DXB", at(2, 2, 0), at(2, 6, 50), "310.00", "USD");
    private static final FlightLeg DWC_JFK = leg("SK901", "DWC", "JFK", at(2, 10, 30), at(3, 0, 30), "930.00", "USD");
    private static final Connection TRANSFER =
            new Connection("DXB", "DWC", Duration.ofMinutes(220), Duration.ofMinutes(120));

    @Test
    void should_describe_direct_flight() {
        var direct = Itinerary.direct(CMB_DXB);

        assertThat(direct.stops()).isZero();
        assertThat(direct.connections()).isEmpty();
        assertThat(direct.totalDuration()).isEqualTo(Duration.ofMinutes(290));
    }

    @Test
    void should_measure_total_duration_from_first_departure_to_last_arrival() {
        var oneStop = Itinerary.oneStop(CMB_DXB, DWC_JFK, TRANSFER);

        assertThat(oneStop.stops()).isEqualTo(1);
        assertThat(oneStop.totalDuration()).isEqualTo(Duration.ofHours(22).plusMinutes(30));
    }

    @Test
    void should_sum_leg_fares() {
        var oneStop = Itinerary.oneStop(CMB_DXB, DWC_JFK, TRANSFER);

        assertThat(oneStop.totalFare()).isEqualByComparingTo("1240.00");
        assertThat(oneStop.currency()).isEqualTo("USD");
    }

    @Test
    void should_flag_connection_that_changes_airport() {
        assertThat(TRANSFER.requiresAirportTransfer()).isTrue();
        assertThat(new Connection("DXB", "DXB", Duration.ofMinutes(100), Duration.ZERO).requiresAirportTransfer())
                .isFalse();
    }

    @Test
    void should_reject_legs_in_different_currencies() {
        var inEuro = leg("SK901", "DWC", "JFK", at(2, 10, 30), at(3, 0, 30), "930.00", "EUR");

        assertThatIllegalArgumentException().isThrownBy(() -> Itinerary.oneStop(CMB_DXB, inEuro, TRANSFER));
    }

    private static FlightLeg leg(String number, String from, String to, OffsetDateTime dep, OffsetDateTime arr,
                                 String fare, String currency) {
        return new FlightLeg(number, from, to, dep, arr, "B777", new BigDecimal(fare), currency);
    }

    private static OffsetDateTime at(int day, int hour, int minute) {
        return OffsetDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneOffset.UTC);
    }
}
