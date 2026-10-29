package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.Cabin;
import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatSpec;
import com.skyhub.booking.domain.SeatStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class HoldServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    private static final String HOLD_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private final SeatInventoryRepository seats = mock(SeatInventoryRepository.class);
    private final SeatInventoryInitializer inventory = mock(SeatInventoryInitializer.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeatMapCache seatMapCache = new SeatMapCache(registry);
    private final Map<String, SeatInventory> rows = new HashMap<>();
    private HoldService service;

    @BeforeEach
    void setUp() {
        for (String flight : List.of("SK501", "SK901")) {
            for (String seat : List.of("11D", "11E", "24A")) {
                rows.put(flight + seat, SeatInventory.available(flight, DATE,
                        new SeatSpec(Integer.parseInt(seat.substring(0, 2)), seat.substring(2), Cabin.ECONOMY)));
            }
        }
        when(seats.findByFlightNumberAndDepartureDateAndSeatNumber(anyString(), eq(DATE), anyString()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.<String>getArgument(0) + call.<String>getArgument(2))));
        when(seats.findByHoldId(HOLD_ID)).thenAnswer(call -> rows.values().stream()
                .filter(seat -> HOLD_ID.equals(seat.getHoldId())).toList());
        service = new HoldService(seats, inventory, seatMapCache, Clock.fixed(NOW, ZoneOffset.UTC),
                TransactionOperations.withoutTransaction(), new HoldMetrics(registry), () -> HOLD_ID, Duration.ofMinutes(10));
    }

    private static HeldSeat seat(String flight, String seat) {
        return new HeldSeat(flight, DATE, seat);
    }

    private double count(String outcome) {
        return registry.counter("skyhub.seat.holds", "outcome", outcome).count();
    }

    @Test
    void should_hold_every_requested_seat_for_the_configured_time() {
        HoldView hold = service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"), seat("SK901", "24A"))));

        assertThat(hold.holdId()).isEqualTo(HOLD_ID);
        assertThat(hold.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(hold.items()).hasSize(2);
        assertThat(rows.get("SK50111D").getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(rows.get("SK90124A").getHoldId()).isEqualTo(HOLD_ID);
        assertThat(rows.get("SK50111E").getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(count("created")).isEqualTo(1);
    }

    @Test
    void should_prepare_inventory_for_each_flight_once() {
        service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"), seat("SK501", "11E"), seat("SK901", "24A"))));

        verify(inventory, times(1)).ensureInventory("SK501", DATE);
        verify(inventory, times(1)).ensureInventory("SK901", DATE);
    }

    @Test
    void should_refuse_the_whole_request_when_one_seat_is_taken_and_hold_nothing() {
        rows.get("SK90124A").book("OTHER1");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"), seat("SK901", "24A")))));

        assertThat(rows.get("SK50111D").getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(count("conflict")).isEqualTo(1);
        assertThat(count("created")).isZero();
    }

    @Test
    void should_not_let_a_second_hold_take_seats_already_held() {
        service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"))));

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D")))));
    }

    @Test
    void should_reject_requests_that_make_no_sense() {
        var many = Collections.nCopies(37, seat("SK501", "11D"));

        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.createHold(new CreateHoldCommand(List.of())));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.createHold(new CreateHoldCommand(many)));
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"), seat("SK501", "11D")))))
                .withMessageContaining("11D");
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.createHold(new CreateHoldCommand(List.of(seat("SK501", "99K")))))
                .withMessageContaining("99K");
    }

    @Test
    void should_not_hold_seats_on_a_flight_that_does_not_operate() {
        org.mockito.Mockito.doThrow(new FlightNotFoundException("SK999 does not operate on " + DATE))
                .when(inventory).ensureInventory("SK999", DATE);

        assertThatExceptionOfType(FlightNotFoundException.class)
                .isThrownBy(() -> service.createHold(new CreateHoldCommand(List.of(seat("SK999", "11D")))));
    }

    @Test
    void should_release_every_seat_of_a_hold_and_show_fresh_maps() {
        service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"), seat("SK901", "24A"))));
        seatMapCache.get("SK501", DATE, List::of); // a cached map that still shows the seats as held

        service.releaseHold(HOLD_ID);

        assertThat(rows.values()).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SeatStatus.AVAILABLE));
        assertThat(seatMapCache.get("SK501", DATE, () -> List.of(
                new com.skyhub.booking.application.view.SeatView("1A", 1, "A", Cabin.BUSINESS, SeatStatus.AVAILABLE)))).hasSize(1);
        assertThat(count("released")).isEqualTo(1);
    }

    @Test
    void should_treat_releasing_an_unknown_or_repeated_hold_as_fine() {
        service.releaseHold("11111111-1111-1111-1111-111111111111");
        service.createHold(new CreateHoldCommand(List.of(seat("SK501", "11D"))));
        service.releaseHold(HOLD_ID);
        service.releaseHold(HOLD_ID);

        assertThat(count("released")).isEqualTo(1);
    }
}
