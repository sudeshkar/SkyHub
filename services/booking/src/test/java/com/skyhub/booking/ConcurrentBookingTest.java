package com.skyhub.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.booking.application.BookingService;
import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.application.PassengerCommand;
import com.skyhub.booking.application.SeatMapService;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatStatus;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import com.skyhub.booking.domain.SeatUnavailableException;

/** The guarantee behind ADR-0002: ten people click the same seat at the same moment; exactly one gets it. */
@SpringBootTest
@Import({PostgresTestcontainer.class, TestFlightCatalog.class})
class ConcurrentBookingTest {

    private static final int BUYERS = 10;
    private static final LocalDate DATE = LocalDate.of(2026, 12, 24);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private SeatMapService seatMapService;

    @Test
    void should_sell_a_seat_exactly_once_under_concurrent_requests() throws Exception {
        seatMapService.seatMap("SK501", DATE); // inventory exists before the race starts
        CountDownLatch startingGun = new CountDownLatch(1);
        List<Future<Outcome>> outcomes = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(BUYERS)) {
            for (int i = 0; i < BUYERS; i++) {
                String buyer = "Buyer" + i;
                outcomes.add(pool.submit(() -> {
                    startingGun.await();
                    return attempt(buyer);
                }));
            }
            startingGun.countDown();

            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> outcome : outcomes) {
                results.add(outcome.get());
            }

            assertThat(results).filteredOn(o -> o == Outcome.BOOKED).hasSize(1);
            assertThat(results).filteredOn(o -> o == Outcome.REFUSED).hasSize(BUYERS - 1);
        }

        assertThat(seatMapService.seatMap("SK501", DATE))
                .filteredOn(seat -> seat.seatNumber().equals("12A"))
                .singleElement()
                .satisfies(seat -> assertThat(seat.status()).isEqualTo(SeatStatus.BOOKED));
    }

    private Outcome attempt(String lastName) {
        try {
            bookingService.createPnr(TestCommands.pnr("buyer@example.com",
                    List.of(new PnrSegment("SK501", DATE)),
                    List.of(new PassengerCommand("Test", lastName, List.of("12A")))), UUID.randomUUID().toString());
            return Outcome.BOOKED;
        } catch (SeatUnavailableException | OptimisticLockingFailureException refused) {
            return Outcome.REFUSED;
        }
    }

    private enum Outcome { BOOKED, REFUSED }
}
