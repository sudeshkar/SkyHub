package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.BoardingPassVerifier.Reason;
import com.skyhub.booking.domain.BoardingWindow;
import com.skyhub.booking.domain.CheckIn;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.security.HmacBoardingPassSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A gate scans a pass and the passenger boards (ADR-0060): the pass must be good (ADR-0056), and a passenger boards once. Whoever
 * scans is recorded, and a pass that has been used is refused the second time.
 */
class BoardingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T08:30:00Z");

    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final CheckInRepository checkIns = mock(CheckInRepository.class);
    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HmacBoardingPassSigner signer = new HmacBoardingPassSigner("0123456789abcdef0123456789abcdef".getBytes());
    private final FakeBoardedPassengers boarded = new FakeBoardedPassengers();
    private final List<CheckIn> stored = new ArrayList<>();
    private BoardingService service;
    private BoardingPassVerifier verifier;

    @BeforeEach
    void setUp() {
        Pnr pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"), List.of(new PnrSegment("SK501", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D")), new PnrPassenger("Raj", "Perera", List.of("11E"))),
                NOW.minusSeconds(3600), "key-hash", "fingerprint");
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        when(checkIns.findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(anyString(), anyInt())).thenAnswer(call -> List.copyOf(stored));
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any())).thenReturn(Optional.empty());
        BoardingPassMetrics metrics = new BoardingPassMetrics(registry);
        verifier = new BoardingPassVerifier(pnrs, checkIns, disruptions, boarded, signer, metrics);
        service = serviceWithDeparture(NOW.plus(Duration.ofMinutes(30)));   // 30 minutes out: inside the 45-to-10 window
        stored.add(CheckIn.of("ABC234", 0, 0, 7, NOW.minusSeconds(7200)));
        stored.add(CheckIn.of("ABC234", 0, 1, 8, NOW.minusSeconds(7200)));
    }

    private BoardingService serviceWithDeparture(Instant departure) {
        FlightCatalog timetable = (flight, date) -> Optional.of(new FlightInfo(departure, Money.of("100.00", "USD")));
        return new BoardingService(verifier, boarded, Clock.fixed(NOW, ZoneOffset.UTC), new BoardingPassMetrics(registry),
                new ExpectedDepartures(disruptions, timetable), new BoardingWindow(Duration.ofMinutes(45), Duration.ofMinutes(10)));
    }

    private String passOf(int passengerIndex) {
        String name = passengerIndex == 0 ? "PERERA/PRIYA" : "PERERA/RAJ";
        return signer.sign(BoardingPassPayload.of(name, "ABC234", "SK501", DATE, passengerIndex == 0 ? "11D" : "11E", 7 + passengerIndex));
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.boarding_pass.boardings").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_board_a_passenger_with_a_good_pass_and_record_who_scanned_it_and_when() {
        var verdict = service.board(passOf(0), "gate.one");

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.pass().passengerName()).isEqualTo("PERERA/PRIYA");
        assertThat(boarded.rows).containsExactly(new FakeBoardedPassengers.Row("ABC234", 0, 0, "gate.one", NOW));
        assertThat(count("boarded")).isEqualTo(1);
    }

    @Test
    void should_refuse_the_same_pass_the_second_time_and_record_nothing_more() {
        service.board(passOf(0), "gate.one");

        var again = service.board(passOf(0), "gate.two");

        assertThat(again.valid()).isFalse();
        assertThat(again.reason()).isEqualTo(Reason.ALREADY_BOARDED);
        assertThat(again.pass()).isNull();
        assertThat(boarded.rows).hasSize(1);
        assertThat(boarded.rows.getFirst().by()).isEqualTo("gate.one");   // the first scan is the one that counts
        assertThat(count("already_boarded")).isEqualTo(1);
    }

    @Test
    void should_board_each_passenger_on_their_own_pass() {
        service.board(passOf(0), "gate.one");

        assertThat(service.board(passOf(1), "gate.one").valid()).isTrue();
        assertThat(boarded.rows).hasSize(2);
    }

    @Test
    void should_board_nobody_on_a_pass_that_is_not_good_and_say_why() {
        stored.removeIf(c -> c.getPassengerIndex() == 0);   // Priya checked out

        var verdict = service.board(passOf(0), "gate.one");

        assertThat(verdict.reason()).isEqualTo(Reason.NOT_CHECKED_IN);
        assertThat(service.board("garbage", "gate.one").reason()).isEqualTo(Reason.BAD_SIGNATURE);
        assertThat(boarded.rows).isEmpty();
        assertThat(count("not_checked_in")).isEqualTo(1);
        assertThat(count("bad_signature")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_good_pass_before_boarding_opens_and_board_nobody() {
        var early = serviceWithDeparture(NOW.plus(Duration.ofHours(3))).board(passOf(0), "gate.one");

        assertThat(early.valid()).isFalse();
        assertThat(early.reason()).isEqualTo(Reason.BOARDING_NOT_OPEN);
        assertThat(early.pass()).isNull();
        assertThat(boarded.rows).isEmpty();
        assertThat(count("boarding_not_open")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_good_pass_once_boarding_has_closed() {
        var late = serviceWithDeparture(NOW.plus(Duration.ofMinutes(10))).board(passOf(0), "gate.one");

        assertThat(late.reason()).isEqualTo(Reason.BOARDING_CLOSED);
        assertThat(boarded.rows).isEmpty();
        assertThat(count("boarding_closed")).isEqualTo(1);
    }

    @Test
    void should_open_boarding_by_the_revised_time_when_the_flight_is_delayed() {
        // Scheduled 30 minutes away, so open; a two-hour delay moves the opening to 75 minutes from now.
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), any()))
                .thenReturn(Optional.of(Disruption.delayed("SK501", DATE, NOW.plus(Duration.ofMinutes(150)), "weather", NOW)));

        assertThat(service.board(passOf(0), "gate.one").reason()).isEqualTo(Reason.BOARDING_NOT_OPEN);
    }

    @Test
    void should_still_say_already_boarded_rather_than_a_time_reason_for_a_used_pass() {
        service.board(passOf(0), "gate.one");

        assertThat(serviceWithDeparture(NOW.plus(Duration.ofMinutes(5))).board(passOf(0), "gate.two").reason()).isEqualTo(Reason.ALREADY_BOARDED);
    }

    @Test
    void should_not_apply_the_time_rule_to_only_checking_a_pass() {
        assertThat(verifier.verify(passOf(0)).valid()).isTrue();   // checking is read-only and has no clock (ADR-0056)
    }

    @Test
    void should_report_a_passenger_who_has_boarded_when_a_pass_is_only_checked() {
        service.board(passOf(0), "gate.one");

        var checked = verifier.verify(passOf(0));

        assertThat(checked.valid()).isFalse();
        assertThat(checked.reason()).isEqualTo(Reason.ALREADY_BOARDED);
        assertThat(verifier.verify(passOf(1)).valid()).isTrue();   // checking never boards anyone
        assertThat(boarded.rows).hasSize(1);
    }
}
