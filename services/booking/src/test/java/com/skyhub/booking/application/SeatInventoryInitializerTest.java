package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.SeatLayout;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class SeatInventoryInitializerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);

    private final SeatInventoryRepository seats = mock(SeatInventoryRepository.class);
    private final FlightCatalog flights = mock(FlightCatalog.class);
    private final SeatInventoryInitializer initializer = new SeatInventoryInitializer(seats, SeatLayout.standard(),
            flights, TransactionOperations.withoutTransaction());

    @Test
    void should_create_seats_once_and_then_answer_from_memory() {
        when(flights.operates("SK501", DATE)).thenReturn(true);

        initializer.ensureInventory("SK501", DATE);
        initializer.ensureInventory("SK501", DATE);
        initializer.ensureInventory("SK501", DATE);

        verify(seats, times(1)).saveAllAndFlush(anyList());
        verify(seats, times(1)).existsByFlightNumberAndDepartureDate("SK501", DATE);
    }

    @Test
    void should_create_nothing_for_a_flight_that_does_not_operate_and_not_touch_the_database() {
        when(flights.operates("SK999", DATE)).thenReturn(false);

        assertThatExceptionOfType(FlightNotFoundException.class).isThrownBy(() -> initializer.ensureInventory("SK999", DATE));
        verifyNoInteractions(seats);
    }

    @Test
    void should_not_recreate_rows_that_already_exist() {
        when(flights.operates("SK501", DATE)).thenReturn(true);
        when(seats.existsByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(true);

        initializer.ensureInventory("SK501", DATE);

        verify(seats, never()).saveAllAndFlush(anyList());
    }

    @Test
    void should_keep_existing_flights_usable_while_the_timetable_is_down() {
        when(flights.operates("SK501", DATE)).thenThrow(new FlightCatalogUnavailableException("down", null));
        when(seats.existsByFlightNumberAndDepartureDate("SK501", DATE)).thenReturn(true);

        initializer.ensureInventory("SK501", DATE);

        verify(seats, never()).saveAllAndFlush(anyList());
    }

    @Test
    void should_refuse_new_flights_while_the_timetable_is_down() {
        when(flights.operates("SK501", DATE)).thenThrow(new FlightCatalogUnavailableException("down", null));

        assertThatExceptionOfType(FlightCatalogUnavailableException.class)
                .isThrownBy(() -> initializer.ensureInventory("SK501", DATE));
        verify(seats, never()).saveAllAndFlush(anyList());
    }

    @Test
    void should_create_seats_exactly_once_when_many_requests_race() throws Exception {
        when(flights.operates("SK501", DATE)).thenReturn(true);
        when(seats.saveAllAndFlush(anyList())).thenAnswer(call -> {
            Thread.sleep(50); // widen the race window
            return call.getArgument(0);
        });
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    initializer.ensureInventory("SK501", DATE);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        }

        verify(seats, times(1)).saveAllAndFlush(any());
    }
}
