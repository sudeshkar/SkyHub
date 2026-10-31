package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.BoardingPassVerifier.Reason;
import com.skyhub.booking.domain.CheckIn;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.Disruption;
import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrPassenger;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.persistence.CheckInRepository;
import com.skyhub.booking.persistence.DisruptionRepository;
import com.skyhub.booking.persistence.PnrRepository;
import com.skyhub.booking.security.HmacBoardingPassSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A gate asks whether a scanned pass is still good (ADR-0056). A good pass is signed by us AND is exactly the pass that would be
 * issued for the booking now: a pass that was checked out of, or replaced by a new check-in, or whose booking or flight was
 * cancelled, is refused even though its signature is still genuine (ADR-0055).
 */
class BoardingPassVerifierTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");

    private final PnrRepository pnrs = mock(PnrRepository.class);
    private final CheckInRepository checkIns = mock(CheckInRepository.class);
    private final DisruptionRepository disruptions = mock(DisruptionRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HmacBoardingPassSigner signer = new HmacBoardingPassSigner("0123456789abcdef0123456789abcdef".getBytes());
    private final List<CheckIn> stored = new ArrayList<>();
    private Pnr pnr;
    private BoardingPassVerifier verifier;

    @BeforeEach
    void setUp() {
        pnr = Pnr.confirmed("ABC234", new ContactEmail("priya@example.com"),
                List.of(new PnrSegment("SK501", DATE), new PnrSegment("SK901", DATE)),
                List.of(new PnrPassenger("Priya", "Perera", List.of("11D", "24A")),
                        new PnrPassenger("Anna Maria", "De Silva", List.of("11E", "24B"))),
                NOW.minusSeconds(3600), "key-hash", "fingerprint");
        when(pnrs.findByLocator("ABC234")).thenReturn(Optional.of(pnr));
        when(checkIns.findByLocatorAndSegmentIndexOrderByPassengerIndexAsc(anyString(), anyInt()))
                .thenAnswer(call -> stored.stream()
                        .filter(c -> c.getLocator().equals(call.getArgument(0)) && c.getSegmentIndex() == (int) call.getArgument(1)).toList());
        when(disruptions.findByFlightNumberAndDepartureDate(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
        verifier = new BoardingPassVerifier(pnrs, checkIns, disruptions, signer, new BoardingPassMetrics(registry));
    }

    /** Checks passenger {@code p} in on flight 0 with this boarding number, and returns the barcode printed for them. */
    private String checkedIn(int passengerIndex, int sequence) {
        stored.add(CheckIn.of("ABC234", 0, passengerIndex, sequence, NOW));
        return barcode(passengerIndex, sequence, pnr.seatOf(passengerIndex, 0));
    }

    private String barcode(int passengerIndex, int sequence, String seat) {
        PnrPassenger p = pnr.getPassengers().get(passengerIndex);
        String name = (p.getLastName() + "/" + p.getFirstName()).toUpperCase(java.util.Locale.ROOT);
        return signer.sign(BoardingPassPayload.of(name, "ABC234", "SK501", DATE, seat, sequence));
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.boarding_pass.verifications").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_accept_the_pass_of_a_passenger_who_is_checked_in_and_say_who_and_where() {
        var verdict = verifier.verify(checkedIn(0, 7));

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.reason()).isNull();
        assertThat(verdict.pass().passengerName()).isEqualTo("PERERA/PRIYA");
        assertThat(verdict.pass().seatNumber()).isEqualTo("11D");
        assertThat(verdict.pass().sequence()).isEqualTo(7);
        assertThat(count("valid")).isEqualTo(1);
    }

    @Test
    void should_accept_a_passenger_whose_name_has_spaces() {
        assertThat(verifier.verify(checkedIn(1, 8)).valid()).isTrue();
    }

    @Test
    void should_refuse_a_pass_that_was_not_signed_by_us_or_was_changed_without_looking_anything_up() {
        String genuine = checkedIn(0, 7);

        for (String bad : new String[] {genuine.replace("11D", "1A"), "M1PERERA/PRIYA ABC234 SK501 2026-11-02 11D 0007.forged", "", "garbage",
                "x".repeat(1000)}) {
            var verdict = verifier.verify(bad);
            assertThat(verdict.valid()).as(bad).isFalse();
            assertThat(verdict.reason()).isEqualTo(Reason.BAD_SIGNATURE);
        }
        verifyNoInteractions(pnrs, checkIns, disruptions);
    }

    @Test
    void should_refuse_a_signed_pass_for_a_booking_that_does_not_exist() {
        String barcode = signer.sign(BoardingPassPayload.of("PERERA/PRIYA", "ZZZ999", "SK501", DATE, "11D", 7));
        when(pnrs.findByLocator("ZZZ999")).thenReturn(Optional.empty());

        assertThat(verifier.verify(barcode).reason()).isEqualTo(Reason.UNKNOWN_BOOKING);
    }

    @Test
    void should_refuse_the_pass_once_the_passenger_has_checked_out() {
        String barcode = checkedIn(0, 7);
        stored.clear();   // checking out (ADR-0055)

        var verdict = verifier.verify(barcode);

        assertThat(verdict.valid()).isFalse();
        assertThat(verdict.reason()).isEqualTo(Reason.NOT_CHECKED_IN);
        assertThat(verdict.pass()).isNull();   // a refused pass tells the gate nothing about the passenger
        assertThat(count("not_checked_in")).isEqualTo(1);
    }

    @Test
    void should_refuse_the_old_pass_after_a_new_seat_and_a_new_check_in_and_accept_the_new_one() {
        String old = checkedIn(0, 7);
        stored.clear();                            // check out ...
        pnr.changeSeat(0, 0, "30C");               // ... change seat ...
        String fresh = checkedIn(0, 20);           // ... check in again: new seat, new number

        assertThat(verifier.verify(old).reason()).isEqualTo(Reason.SUPERSEDED);
        assertThat(verifier.verify(fresh).valid()).isTrue();
    }

    @Test
    void should_say_not_checked_in_for_a_passenger_who_is_out_while_another_on_the_booking_is_in() {
        String priya = checkedIn(0, 7);
        checkedIn(1, 8);
        stored.removeIf(c -> c.getPassengerIndex() == 0);   // Priya checks out (ADR-0058); Raj stays in

        var verdict = verifier.verify(priya);

        assertThat(verdict.reason()).isEqualTo(Reason.NOT_CHECKED_IN);
        assertThat(verifier.verify(barcode(1, 8, "11E")).valid()).isTrue();
    }

    @Test
    void should_refuse_a_pass_with_the_old_number_even_if_the_seat_is_the_same() {
        String old = checkedIn(0, 7);
        stored.clear();
        checkedIn(0, 20);   // same seat, new number

        assertThat(verifier.verify(old).reason()).isEqualTo(Reason.SUPERSEDED);
    }

    @Test
    void should_refuse_the_pass_of_a_cancelled_booking() {
        String barcode = checkedIn(0, 7);
        pnr.cancel();

        assertThat(verifier.verify(barcode).reason()).isEqualTo(Reason.BOOKING_CANCELLED);
    }

    @Test
    void should_refuse_the_pass_of_a_cancelled_flight() {
        String barcode = checkedIn(0, 7);
        when(disruptions.findByFlightNumberAndDepartureDate("SK501", DATE))
                .thenReturn(Optional.of(Disruption.cancelled("SK501", DATE, "Aircraft grounded", NOW)));

        assertThat(verifier.verify(barcode).reason()).isEqualTo(Reason.FLIGHT_CANCELLED);
    }

    @Test
    void should_refuse_a_signed_pass_for_a_flight_the_booking_does_not_have() {
        String barcode = signer.sign(BoardingPassPayload.of("PERERA/PRIYA", "ABC234", "SK777", DATE, "11D", 7));

        assertThat(verifier.verify(barcode).reason()).isEqualTo(Reason.NOT_CHECKED_IN);
    }

    @Test
    void should_refuse_a_signed_pass_in_the_name_of_someone_not_on_the_booking() {
        checkedIn(0, 7);
        String barcode = signer.sign(BoardingPassPayload.of("SOMEONE/ELSE", "ABC234", "SK501", DATE, "11D", 7));

        assertThat(verifier.verify(barcode).reason()).isEqualTo(Reason.SUPERSEDED);
    }
}
