package com.skyhub.booking.application.rebooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.FlightCatalogUnavailableException;
import com.skyhub.booking.domain.BookingCancelledException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.SeatUnavailableException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class BulkRebookingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final PnrSegment FROM = new PnrSegment("SK501", DATE);
    private static final PnrSegment TO = new PnrSegment("SK502", DATE);

    private final RebookingService rebooking = mock(RebookingService.class);
    private final BulkRebookingService service = new BulkRebookingService(rebooking);

    private static RebookCommand move(String locator) {
        return new RebookCommand(locator, 0, "SK502", DATE, "sam.ops");
    }

    private static RebookingResult moved(String locator, boolean changed) {
        return new RebookingResult(locator, 0, FROM, TO, List.of("14A", "14B"), changed);
    }

    private void answer(String locator, Object outcome) {
        var call = when(rebooking.rebook(any(RebookCommand.class)));
        if (outcome instanceof RuntimeException failure) {
            call.thenThrow(failure);
        } else {
            call.thenReturn((RebookingResult) outcome);
        }
    }

    private void answersByLocator(java.util.Map<String, Object> outcomes) {
        when(rebooking.rebook(any(RebookCommand.class))).thenAnswer(call -> {
            Object outcome = outcomes.get(call.<RebookCommand>getArgument(0).locator());
            if (outcome instanceof RuntimeException failure) {
                throw failure;
            }
            return outcome;
        });
    }

    @Test
    void should_report_each_move_in_request_order_with_its_new_seats() {
        answersByLocator(java.util.Map.of("AAAAAA", moved("AAAAAA", true), "BBBBBB", moved("BBBBBB", false)));

        List<MoveResult> results = service.rebookAll(List.of(move("AAAAAA"), move("BBBBBB")));

        assertThat(results).extracting(MoveResult::locator).containsExactly("AAAAAA", "BBBBBB");
        assertThat(results).extracting(MoveResult::outcome).containsExactly(MoveOutcome.MOVED, MoveOutcome.UNCHANGED);
        assertThat(results.getFirst().seats()).containsExactly("14A", "14B");
        assertThat(results.getFirst().detail()).isNull();
    }

    @Test
    void should_say_why_a_move_was_refused_and_carry_on_with_the_rest() {
        answersByLocator(java.util.Map.of(
                "AAAAAA", new RebookingRefusedException("SK502 flies DXB to JFK, not the booked route DXB to LHR"),
                "BBBBBB", moved("BBBBBB", true),
                "CCCCCC", new BookingCancelledException("This booking is cancelled"),
                "DDDDDD", new InvalidBookingException("This booking has no flight 3")));

        List<MoveResult> results = service.rebookAll(List.of(move("AAAAAA"), move("BBBBBB"), move("CCCCCC"), move("DDDDDD")));

        assertThat(results).extracting(MoveResult::outcome)
                .containsExactly(MoveOutcome.REFUSED, MoveOutcome.MOVED, MoveOutcome.REFUSED, MoveOutcome.REFUSED);
        assertThat(results.getFirst().detail()).isEqualTo("SK502 flies DXB to JFK, not the booked route DXB to LHR");
        assertThat(results.get(2).detail()).isEqualTo("This booking is cancelled");
        assertThat(results.getFirst().seats()).isNull();
    }

    @Test
    void should_tell_an_unknown_booking_from_a_refusal() {
        answer("AAAAAA", new PnrNotFoundException("No booking matches this reference"));

        assertThat(service.rebookAll(List.of(move("AAAAAA")))).extracting(MoveResult::outcome).containsExactly(MoveOutcome.NOT_FOUND);
    }

    @Test
    void should_report_a_seat_taken_meanwhile_as_a_conflict_to_try_again() {
        answersByLocator(java.util.Map.of("AAAAAA", new SeatUnavailableException("Seat 14A is taken"),
                "BBBBBB", new OptimisticLockingFailureException("stale")));

        List<MoveResult> results = service.rebookAll(List.of(move("AAAAAA"), move("BBBBBB")));

        assertThat(results).extracting(MoveResult::outcome).containsExactly(MoveOutcome.CONFLICT, MoveOutcome.CONFLICT);
        assertThat(results).allSatisfy(r -> assertThat(r.detail()).contains("try again"));
    }

    @Test
    void should_stop_when_the_timetable_is_down_and_not_try_the_rest() {
        answersByLocator(java.util.Map.of("AAAAAA", moved("AAAAAA", true),
                "BBBBBB", new FlightCatalogUnavailableException("down", null)));

        List<MoveResult> results = service.rebookAll(List.of(move("AAAAAA"), move("BBBBBB"), move("CCCCCC"), move("DDDDDD")));

        assertThat(results).extracting(MoveResult::outcome).containsExactly(
                MoveOutcome.MOVED, MoveOutcome.UNAVAILABLE, MoveOutcome.NOT_ATTEMPTED, MoveOutcome.NOT_ATTEMPTED);
        assertThat(results.get(3).locator()).isEqualTo("DDDDDD");
        verify(rebooking, times(2)).rebook(any(RebookCommand.class));
    }

    @Test
    void should_stop_on_an_unexpected_error_and_keep_what_was_already_moved() {
        answersByLocator(java.util.Map.of("AAAAAA", moved("AAAAAA", true), "BBBBBB", new IllegalStateException("boom: secret@example.com")));

        List<MoveResult> results = service.rebookAll(List.of(move("AAAAAA"), move("BBBBBB"), move("CCCCCC")));

        assertThat(results).extracting(MoveResult::outcome).containsExactly(MoveOutcome.MOVED, MoveOutcome.FAILED, MoveOutcome.NOT_ATTEMPTED);
        assertThat(results.get(1).detail()).doesNotContain("secret").doesNotContain("boom");   // the cause goes to the log, not the caller
    }

    @Test
    void should_refuse_none_or_too_many_before_changing_anything() {
        List<RebookCommand> tooMany = IntStream.rangeClosed(1, BulkRebookingService.MAX_MOVES + 1)
                .mapToObj(i -> move("L%05d".formatted(i))).toList();

        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.rebookAll(List.of()));
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> service.rebookAll(tooMany));
        verifyNoInteractions(rebooking);
    }

    @Test
    void should_accept_exactly_the_most_moves_allowed() {
        List<RebookCommand> exactly = new ArrayList<>(IntStream.rangeClosed(1, BulkRebookingService.MAX_MOVES)
                .mapToObj(i -> move("L%05d".formatted(i))).toList());
        when(rebooking.rebook(any(RebookCommand.class))).thenAnswer(call -> moved(call.<RebookCommand>getArgument(0).locator(), true));

        assertThat(service.rebookAll(exactly)).hasSize(BulkRebookingService.MAX_MOVES);
    }

    @Test
    void should_refuse_the_same_booking_and_flight_twice_as_ambiguous() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> service.rebookAll(List.of(move("AAAAAA"), move("AAAAAA"))))
                .withMessageContaining("AAAAAA");
        verify(rebooking, never()).rebook(any(RebookCommand.class));
    }

    @Test
    void should_allow_two_different_flights_of_one_booking() {
        when(rebooking.rebook(any(RebookCommand.class))).thenAnswer(call -> {
            RebookCommand asked = call.getArgument(0);
            return new RebookingResult(asked.locator(), asked.segmentIndex(), FROM, TO, List.of("14A"), true);
        });

        List<MoveResult> results = service.rebookAll(List.of(move("AAAAAA"),
                new RebookCommand("AAAAAA", 1, "SK502", DATE, "sam.ops")));

        assertThat(results).extracting(MoveResult::segmentIndex).containsExactly(0, 1);
    }
}
