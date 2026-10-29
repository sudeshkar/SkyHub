package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.Cabin;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.domain.SeatInventory;
import com.skyhub.booking.domain.SeatSpec;
import com.skyhub.booking.domain.SeatStatus;
import com.skyhub.booking.domain.SeatUnavailableException;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class ManageBookingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant TOKEN_EXPIRY = Instant.parse("2026-10-29T10:15:00Z");

    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final SeatInventoryRepository seats = mock(SeatInventoryRepository.class);
    private final com.skyhub.booking.persistence.CheckInRepository checkIns =
            mock(com.skyhub.booking.persistence.CheckInRepository.class);
    private final ManageTokenIssuer tokens = mock(ManageTokenIssuer.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeatMapCache seatMapCache = new SeatMapCache(registry);
    private final Map<String, SeatInventory> inventory = new HashMap<>();
    private Pnr pnr;
    private ManageBookingService service;

    @BeforeEach
    void setUp() {
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", DATE), new PnrSegment("SK901", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A")),
                        new PnrPassenger("Raj", "Perera", List.of("11E", "24B"))),
                Instant.parse("2026-10-29T10:00:00Z"), "key-hash", "fingerprint");
        for (String flight : List.of("SK501", "SK901")) {
            for (String seat : List.of("11D", "11E", "24A", "24B", "30C")) {
                inventory.put(flight + seat, SeatInventory.available(flight, DATE,
                        new SeatSpec(Integer.parseInt(seat.substring(0, 2)), seat.substring(2), Cabin.ECONOMY)));
            }
        }
        for (String held : List.of("SK50111D", "SK50111E", "SK90124A", "SK90124B")) {
            inventory.get(held).book("ABC234");
        }
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        when(pnrs.findByLocator("ZZZZZZ")).thenReturn(Optional.empty());
        when(pnrs.saveAndFlush(any(Pnr.class))).thenAnswer(call -> call.getArgument(0));
        when(seats.findByFlightNumberAndDepartureDateAndSeatNumber(anyString(), eq(DATE), anyString()))
                .thenAnswer(call -> Optional.ofNullable(
                        inventory.get(call.<String>getArgument(0) + call.<String>getArgument(2))));
        when(tokens.issue("ABC234")).thenReturn(new ManageToken("signed.jwt.value", TOKEN_EXPIRY));
        service = new ManageBookingService(pnrs, seats, checkIns, seatMapCache, tokens, new ManageMetrics(registry),
                TransactionOperations.withoutTransaction());
    }

    @Test
    void should_freeze_the_seats_of_a_flight_that_is_checked_in() {
        when(checkIns.existsByLocatorAndSegmentIndex("ABC234", 1)).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatExceptionOfType(com.skyhub.booking.domain.AlreadyCheckedInException.class)
                .isThrownBy(() -> service.changeSeat("ABC234", new SeatChangeCommand(0, 1, "30C")))
                .withMessageContaining("checked in");

        assertThat(inventory.get("SK90130C").getStatus()).isEqualTo(com.skyhub.booking.domain.SeatStatus.AVAILABLE);
        assertThat(pnr.seatOf(0, 1)).isEqualTo("24A");
        // The other flight of the same booking can still change.
        service.changeSeat("ABC234", new SeatChangeCommand(0, 0, "30C"));
        assertThat(pnr.seatOf(0, 0)).isEqualTo("30C");
    }

    private double count(String action) {
        return registry.counter("skyhub.manage.actions", "action", action).count();
    }

    @Test
    void should_open_a_session_for_a_matching_last_name_ignoring_case() {
        ManageSession session = service.openSession("ABC234", "  perera ");

        assertThat(session.token().value()).isEqualTo("signed.jwt.value");
        assertThat(session.token().expiresAt()).isEqualTo(TOKEN_EXPIRY);
        assertThat(session.pnr().locator()).isEqualTo("ABC234");
        assertThat(count("session_opened")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_wrong_last_name_without_issuing_a_token() {
        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.openSession("ABC234", "Silva"));

        verifyNoInteractions(tokens);
        assertThat(count("session_denied")).isEqualTo(1);
    }

    @Test
    void should_answer_an_unknown_reference_exactly_like_a_wrong_name() {
        assertThatExceptionOfType(PnrNotFoundException.class)
                .isThrownBy(() -> service.openSession("ZZZZZZ", "Perera"))
                .withMessage("No booking matches this reference and last name");
        assertThat(count("session_denied")).isEqualTo(1);
    }

    @Test
    void should_return_a_booking_and_reject_an_unknown_one() {
        assertThat(service.find("ABC234").locator()).isEqualTo("ABC234");
        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.find("ZZZZZZ"));
    }

    @Test
    void should_cancel_and_release_every_seat() {
        PnrView cancelled = service.cancel("ABC234");

        assertThat(cancelled.status()).isEqualTo(PnrStatus.CANCELLED);
        assertThat(inventory.values()).allSatisfy(seat -> {
            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.getPnrLocator()).isNull();
        });
        assertThat(count("cancelled")).isEqualTo(1);
    }

    @Test
    void should_make_cancel_idempotent_and_leave_seats_rebooked_by_others_alone() {
        service.cancel("ABC234");
        inventory.get("SK50111D").book("OTHER1"); // someone else takes the freed seat

        PnrView again = service.cancel("ABC234");

        assertThat(again.status()).isEqualTo(PnrStatus.CANCELLED);
        assertThat(inventory.get("SK50111D").getPnrLocator()).isEqualTo("OTHER1");
        assertThat(count("cancelled")).isEqualTo(1);
    }

    @Test
    void should_move_a_passenger_booking_the_new_seat_and_releasing_the_old() {
        PnrView updated = service.changeSeat("ABC234", new SeatChangeCommand(0, 1, "30C"));

        assertThat(updated.passengers().getFirst().seats()).containsExactly("11D", "30C");
        assertThat(inventory.get("SK90130C").getPnrLocator()).isEqualTo("ABC234");
        assertThat(inventory.get("SK90124A").getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(inventory.get("SK50111D").getPnrLocator()).isEqualTo("ABC234");
        assertThat(count("seat_changed")).isEqualTo(1);
    }

    @Test
    void should_keep_everything_unchanged_when_the_new_seat_is_taken() {
        inventory.get("SK90130C").book("OTHER1");

        assertThatExceptionOfType(SeatUnavailableException.class)
                .isThrownBy(() -> service.changeSeat("ABC234", new SeatChangeCommand(0, 1, "30C")));

        assertThat(pnr.seatOf(0, 1)).isEqualTo("24A");
        assertThat(inventory.get("SK90124A").getPnrLocator()).isEqualTo("ABC234");
        assertThat(count("seat_taken")).isEqualTo(1);
    }

    @Test
    void should_do_nothing_when_the_requested_seat_is_the_current_one() {
        PnrView same = service.changeSeat("ABC234", new SeatChangeCommand(0, 0, "11D"));

        assertThat(same.passengers().getFirst().seats()).containsExactly("11D", "24A");
        assertThat(count("seat_changed")).isZero();
    }

    @Test
    void should_refuse_seat_changes_on_a_cancelled_booking() {
        service.cancel("ABC234");

        assertThatExceptionOfType(BookingCancelledException.class)
                .isThrownBy(() -> service.changeSeat("ABC234", new SeatChangeCommand(0, 0, "30C")));
    }

    @Test
    void should_reject_a_seat_that_does_not_exist_on_the_flight() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.changeSeat("ABC234", new SeatChangeCommand(0, 0, "99K")))
                .withMessageContaining("99K");
    }

    @Test
    void should_reject_a_passenger_or_flight_that_does_not_exist() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.changeSeat("ABC234", new SeatChangeCommand(5, 0, "30C")));
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.changeSeat("ABC234", new SeatChangeCommand(0, 5, "30C")));
    }

    @Test
    void should_show_fresh_seat_maps_after_a_change() {
        List<com.skyhub.booking.application.view.SeatView> stale = List.of();
        seatMapCache.get("SK901", DATE, () -> stale);

        service.changeSeat("ABC234", new SeatChangeCommand(0, 1, "30C"));

        var fresh = List.of(new com.skyhub.booking.application.view.SeatView("1A", 1, "A", Cabin.BUSINESS, SeatStatus.AVAILABLE));
        assertThat(seatMapCache.get("SK901", DATE, () -> fresh)).isEqualTo(fresh); // loader ran again: entry was cleared
    }
}
