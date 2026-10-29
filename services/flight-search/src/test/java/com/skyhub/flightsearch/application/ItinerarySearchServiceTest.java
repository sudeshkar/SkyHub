package com.skyhub.flightsearch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skyhub.flightsearch.domain.AirportTransfer;
import com.skyhub.flightsearch.domain.FlightSchedule;
import com.skyhub.flightsearch.domain.Hub;
import com.skyhub.flightsearch.domain.HubNetwork;
import com.skyhub.flightsearch.domain.Itinerary;
import com.skyhub.flightsearch.domain.InvalidSearchException;
import com.skyhub.flightsearch.persistence.FlightScheduleRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItinerarySearchServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

    private final List<FlightSchedule> timetable = new ArrayList<>();
    private final FlightScheduleRepository repository = mock(FlightScheduleRepository.class);

    @Test
    void should_return_direct_flight() {
        givenSchedule("SK001", "DXB", "LHR", "08:30", 470);

        var results = search("DXB", "LHR");

        assertThat(results).singleElement().satisfies(it -> {
            assertThat(it.stops()).isZero();
            assertThat(it.legs().getFirst().flightNumber()).isEqualTo("SK001");
        });
    }

    @Test
    void should_exclude_flights_not_operating_on_date() {
        givenSchedule("SK411", "DWC", "SYD", "11:00", 840, "35");

        assertThat(search("DWC", "SYD")).isEmpty();
    }

    @Test
    void should_build_same_hub_connection_when_layover_meets_mct() {
        givenSchedule("SK501", "CMB", "DXB", "02:00", 290);   // arrives 06:50
        givenSchedule("SK001", "DXB", "LHR", "08:30", 470);   // 100 min later, MCT 75

        var results = search("CMB", "LHR");

        assertThat(results).singleElement().satisfies(it -> {
            assertThat(it.connections().getFirst().layover()).isEqualTo(Duration.ofMinutes(100));
            assertThat(it.connections().getFirst().requiresAirportTransfer()).isFalse();
        });
    }

    @Test
    void should_reject_connection_shorter_than_mct() {
        givenSchedule("SK501", "CMB", "DXB", "02:00", 290);   // arrives 06:50
        givenSchedule("SK001", "DXB", "LHR", "07:50", 470);   // 60 min later, MCT 75

        assertThat(search("CMB", "LHR")).isEmpty();
    }

    @Test
    void should_build_cross_airport_connection_flagged_as_transfer() {
        givenSchedule("SK501", "CMB", "DXB", "02:00", 290);   // arrives DXB 06:50
        givenSchedule("SK901", "DWC", "JFK", "10:30", 840);   // 220 min later, needs 180

        var results = search("CMB", "JFK");

        assertThat(results).singleElement().satisfies(it -> {
            var connection = it.connections().getFirst();
            assertThat(connection.requiresAirportTransfer()).isTrue();
            assertThat(connection.transferTime()).isEqualTo(Duration.ofMinutes(120));
            assertThat(connection.layover()).isEqualTo(Duration.ofMinutes(220));
        });
    }

    @Test
    void should_reject_cross_airport_connection_shorter_than_transfer_plus_mct() {
        givenSchedule("SK501", "CMB", "DXB", "02:00", 290);   // arrives DXB 06:50
        givenSchedule("SK901", "DWC", "JFK", "09:30", 840);   // 160 min later, needs 180

        assertThat(search("CMB", "JFK")).isEmpty();
    }

    @Test
    void should_reject_layover_longer_than_maximum() {
        givenSchedule("SK501", "CMB", "DXB", "02:00", 290);   // arrives 06:50
        givenSchedule("SK001", "DXB", "LHR", "19:00", 470);   // 12h10m later, max 12h

        assertThat(search("CMB", "LHR")).isEmpty();
    }

    @Test
    void should_find_overnight_connection_departing_next_day() {
        givenSchedule("SK701", "LHR", "DXB", "20:00", 400);   // arrives 02:40 next day
        givenSchedule("SK702", "DXB", "SIN", "05:00", 440);   // 140 min later, next day

        var results = search("LHR", "SIN");

        assertThat(results).singleElement().satisfies(it ->
                assertThat(it.legs().getLast().departure().toLocalDate()).isEqualTo(MONDAY.plusDays(1)));
    }

    @Test
    void should_never_connect_through_inactive_hub() {
        givenSchedule("SK203", "BOM", "DWC", "04:30", 190);
        givenSchedule("SK901", "DWC", "JFK", "10:30", 840);
        var dwcClosed = new HubNetwork(
                List.of(new Hub("DXB", "Dubai International", true, Duration.ofMinutes(75)),
                        new Hub("DWC", "Al Maktoum International", false, Duration.ofMinutes(60))),
                List.of(new AirportTransfer("DXB", "DWC", Duration.ofMinutes(120))),
                Duration.ofHours(12));

        assertThat(search("BOM", "JFK", dwcClosed)).isEmpty();
    }

    @Test
    void should_reject_origin_equal_to_destination() {
        assertThatExceptionOfType(InvalidSearchException.class)
                .isThrownBy(() -> search("CMB", "CMB"))
                .withMessageContaining("must differ");
    }

    @Test
    void should_sort_by_departure_then_total_duration() {
        givenSchedule("SK003", "DXB", "LHR", "14:00", 470);
        givenSchedule("SK001", "DXB", "LHR", "08:30", 480);
        givenSchedule("SK005", "DXB", "LHR", "08:30", 450);

        var flightNumbers = search("DXB", "LHR").stream()
                .map(it -> it.legs().getFirst().flightNumber())
                .toList();

        assertThat(flightNumbers).containsExactly("SK005", "SK001", "SK003");
    }

    private List<Itinerary> search(String origin, String destination) {
        return search(origin, destination, network());
    }

    private List<Itinerary> search(String origin, String destination, HubNetwork network) {
        when(repository.findByOrigin(origin)).thenReturn(byOrigin(origin));
        when(repository.findByDestination(destination)).thenReturn(byDestination(destination));
        return new ItinerarySearchService(repository, network).search(origin, destination, MONDAY);
    }

    private List<FlightSchedule> byOrigin(String origin) {
        return timetable.stream().filter(s -> s.getOrigin().equals(origin)).toList();
    }

    private List<FlightSchedule> byDestination(String destination) {
        return timetable.stream().filter(s -> s.getDestination().equals(destination)).toList();
    }

    private void givenSchedule(String number, String from, String to, String departs, int minutes) {
        givenSchedule(number, from, to, departs, minutes, "1234567");
    }

    private void givenSchedule(String number, String from, String to, String departs, int minutes, String days) {
        timetable.add(new FlightSchedule(number, from, to, LocalTime.parse(departs), minutes, "B777",
                new BigDecimal("100.00"), "USD", days));
    }

    private static HubNetwork network() {
        return new HubNetwork(
                List.of(new Hub("DXB", "Dubai International", true, Duration.ofMinutes(75)),
                        new Hub("DWC", "Al Maktoum International", true, Duration.ofMinutes(60))),
                List.of(new AirportTransfer("DXB", "DWC", Duration.ofMinutes(120))),
                Duration.ofHours(12));
    }
}
