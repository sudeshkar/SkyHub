package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skyhub.booking.application.view.BookingSummary;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.domain.TravellerAccount;
import com.skyhub.booking.persistence.TravellerAccountRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MyBookingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String INDEX = "index-of-priya";
    /** The UTC date of NOW: what "upcoming" is measured from (ADR-0042). */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    private final TravellerAccountRepository accounts = mock(TravellerAccountRepository.class);
    private final BookingsByAddress bookings = mock(BookingsByAddress.class);
    private final ManageBookingService manage = mock(ManageBookingService.class);
    private final ManageTokenIssuer tokens = mock(ManageTokenIssuer.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MyBookingsService service =
            new MyBookingsService(accounts, bookings, manage, tokens, new MyBookingsMetrics(registry),
                    java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(accounts.findById(7L)).thenReturn(Optional.of(
                TravellerAccount.unverified(new ContactEmail("priya@example.com"), INDEX, NOW)));
    }

    private static BookingSummary summary(String locator) {
        return new BookingSummary(locator, PnrStatus.CONFIRMED,
                List.of(new PnrView.SegmentView("SK001", LocalDate.parse("2026-12-01"))), 2, NOW);
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.my.bookings").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_list_the_bookings_made_with_the_accounts_address() {
        when(bookings.newestAfter(INDEX, BookingFilter.ALL, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(List.of(summary("ABC234"), summary("DEF345")));

        var list = service.list(7L, null, null);

        assertThat(list.bookings()).extracting(BookingSummary::locator).containsExactly("ABC234", "DEF345");
        assertThat(list.truncated()).isFalse();
        assertThat(list.nextCursor()).isNull();
        assertThat(count("listed")).isEqualTo(1);
    }

    @Test
    void should_ask_for_one_more_than_the_limit_to_know_whether_there_are_more() {
        List<BookingSummary> found = new ArrayList<>(IntStream.rangeClosed(1, MyBookingsService.LIMIT + 1)
                .mapToObj(i -> summary("L%05d".formatted(i))).toList());
        when(bookings.newestAfter(INDEX, BookingFilter.ALL, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(found);

        var list = service.list(7L, null, null);

        assertThat(list.bookings()).hasSize(MyBookingsService.LIMIT);
        assertThat(list.truncated()).isTrue();
        assertThat(list.bookings()).doesNotContain(found.get(MyBookingsService.LIMIT));
        // The next page starts after the last booking shown, not after the extra one that was read to know there is more.
        BookingSummary lastShown = list.bookings().getLast();
        assertThat(BookingCursor.decode(list.nextCursor())).isEqualTo(new BookingCursor(lastShown.createdAt(), lastShown.locator()));
    }

    @Test
    void should_start_the_next_page_after_the_position_it_was_given() {
        BookingCursor after = new BookingCursor(NOW.minusSeconds(60), "ABC234");
        when(bookings.newestAfter(INDEX, BookingFilter.ALL, TODAY, after, MyBookingsService.LIMIT + 1)).thenReturn(List.of(summary("DEF345")));

        var list = service.list(7L, after.encode(), null);

        assertThat(list.bookings()).extracting(BookingSummary::locator).containsExactly("DEF345");
        assertThat(list.nextCursor()).isNull();
    }

    @Test
    void should_refuse_a_cursor_it_did_not_make_before_reading_anything() {
        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class)
                .isThrownBy(() -> service.list(7L, "forged", null));
        org.mockito.Mockito.verifyNoInteractions(bookings);
    }

    @Test
    void should_list_nothing_for_an_address_with_no_bookings() {
        when(bookings.newestAfter(INDEX, BookingFilter.ALL, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(List.of());

        var list = service.list(7L, null, null);

        assertThat(list.bookings()).isEmpty();
        assertThat(list.truncated()).isFalse();
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() {
        assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.list(99L, null, null));
    }

    @Test
    void should_open_a_booking_the_account_owns_with_a_manage_token_for_that_booking_only() {
        var token = new ManageToken("signed", NOW.plusSeconds(900));
        var view = mock(PnrView.class);
        when(bookings.owns(INDEX, "ABC234")).thenReturn(true);
        when(tokens.issue("ABC234")).thenReturn(token);
        when(manage.find("ABC234")).thenReturn(view);

        var session = service.openSession(7L, "ABC234");

        assertThat(session.token()).isEqualTo(token);
        assertThat(session.pnr()).isSameAs(view);
        assertThat(count("opened")).isEqualTo(1);
    }

    @Test
    void should_answer_a_booking_of_someone_else_exactly_like_an_unknown_one() {
        when(bookings.owns(INDEX, "ABC234")).thenReturn(false);

        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.openSession(7L, "ABC234"))
                .withMessage("No booking matches this reference");
        assertThatExceptionOfType(PnrNotFoundException.class).isThrownBy(() -> service.openSession(7L, "ZZZZZZ"))
                .withMessage("No booking matches this reference");
        verify(tokens, never()).issue(anyString());
        assertThat(count("denied")).isEqualTo(2);
    }

    @Test
    void should_issue_no_token_for_an_account_that_no_longer_exists() {
        assertThatExceptionOfType(AccountLoginFailedException.class).isThrownBy(() -> service.openSession(99L, "ABC234"));
        verify(tokens, never()).issue(anyString());
    }

    @Test
    void should_ask_for_the_filter_it_was_given_measured_from_todays_utc_date() {
        when(bookings.upcomingSoonest(INDEX, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(List.of(summary("ABC234")));
        when(bookings.pastMostRecent(INDEX, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(List.of(summary("DEF345")));

        assertThat(service.list(7L, null, "upcoming").bookings()).extracting(BookingSummary::locator).containsExactly("ABC234");
        assertThat(service.list(7L, null, "PAST").bookings()).extracting(BookingSummary::locator).containsExactly("DEF345");
    }

    @Test
    void should_page_within_a_filter() {
        BookingCursor after = new BookingCursor(NOW.minusSeconds(60), "ABC234");
        when(bookings.newestAfter(INDEX, BookingFilter.ALL, TODAY, after, MyBookingsService.LIMIT + 1)).thenReturn(List.of(summary("DEF345")));

        assertThat(service.list(7L, after.encode(), "all").bookings()).extracting(BookingSummary::locator).containsExactly("DEF345");
    }

    // ---- past, most recently flown first (ADR-0051) -----------------------------------------------------------------------

    @Test
    void should_page_the_past_list_from_the_position_it_was_given_in_its_own_order() {
        PastCursor after = new PastCursor(LocalDate.parse("2026-09-01"), "ABC234");
        when(bookings.pastMostRecent(INDEX, TODAY, after, MyBookingsService.LIMIT + 1)).thenReturn(List.of(flyingOn("DEF345", "2026-08-05")));

        var list = service.list(7L, after.encode(), "past");

        assertThat(list.bookings()).extracting(BookingSummary::locator).containsExactly("DEF345");
        assertThat(list.nextCursor()).isNull();
    }

    @Test
    void should_start_the_next_past_page_after_the_last_flight_of_the_last_booking_shown() {
        List<BookingSummary> found = new ArrayList<>(IntStream.rangeClosed(1, MyBookingsService.LIMIT + 1)
                .mapToObj(i -> flyingOn("P%05d".formatted(i), "2026-06-01", "2026-09-0" + (1 + i % 9))).toList());   // the return flight is the last one flown
        when(bookings.pastMostRecent(INDEX, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(found);

        var list = service.list(7L, null, "past");

        assertThat(list.bookings()).hasSize(MyBookingsService.LIMIT);
        BookingSummary last = list.bookings().getLast();
        PastCursor next = PastCursor.decode(list.nextCursor());
        assertThat(next.locator()).isEqualTo(last.locator());
        assertThat(next.lastFlight()).isEqualTo(last.segments().get(1).departureDate());   // the later of its flights, the value the query ordered by
    }

    @Test
    void should_refuse_a_past_cursor_anywhere_but_the_past_list_and_the_other_cursors_there() {
        String past = new PastCursor(LocalDate.parse("2026-09-01"), "ABC234").encode();
        String newest = new BookingCursor(NOW, "ABC234").encode();
        String soonest = new UpcomingCursor(LocalDate.parse("2026-12-01"), "ABC234").encode();

        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, past, null));
        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, past, "upcoming"));
        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, newest, "past"));
        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, soonest, "past"));
        org.mockito.Mockito.verifyNoInteractions(bookings);
    }

    @Test
    void should_refuse_a_filter_it_does_not_know_before_reading_anything() {
        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, null, "soon"));
        org.mockito.Mockito.verifyNoInteractions(bookings);
    }

    // ---- upcoming, soonest flight first (ADR-0044) ----------------------------------------------------------------------

    private static BookingSummary flyingOn(String locator, String... dates) {
        return new BookingSummary(locator, PnrStatus.CONFIRMED,
                java.util.Arrays.stream(dates).map(d -> new PnrView.SegmentView("SK001", LocalDate.parse(d))).toList(), 1, NOW);
    }

    @Test
    void should_page_the_upcoming_list_from_the_position_it_was_given_in_its_own_order() {
        UpcomingCursor after = new UpcomingCursor(LocalDate.parse("2026-12-01"), "ABC234");
        when(bookings.upcomingSoonest(INDEX, TODAY, after, MyBookingsService.LIMIT + 1)).thenReturn(List.of(flyingOn("DEF345", "2026-12-05")));

        var list = service.list(7L, after.encode(), "upcoming");

        assertThat(list.bookings()).extracting(BookingSummary::locator).containsExactly("DEF345");
        assertThat(list.nextCursor()).isNull();
    }

    @Test
    void should_start_the_next_upcoming_page_after_the_next_flight_of_the_last_booking_shown() {
        List<BookingSummary> found = new ArrayList<>(IntStream.rangeClosed(1, MyBookingsService.LIMIT + 1)
                .mapToObj(i -> flyingOn("U%05d".formatted(i), "2026-10-01", "2026-12-0" + (1 + i % 9))).toList());   // an old flight, then one to come
        when(bookings.upcomingSoonest(INDEX, TODAY, null, MyBookingsService.LIMIT + 1)).thenReturn(found);

        var list = service.list(7L, null, "upcoming");

        assertThat(list.bookings()).hasSize(MyBookingsService.LIMIT);
        BookingSummary last = list.bookings().getLast();
        UpcomingCursor next = UpcomingCursor.decode(list.nextCursor());
        assertThat(next.locator()).isEqualTo(last.locator());
        // The position is the flight that is still to come, not the one that has gone, or the page would start in the wrong place.
        assertThat(next.nextDeparture()).isAfterOrEqualTo(TODAY).isEqualTo(last.segments().get(1).departureDate());
    }

    @Test
    void should_refuse_the_cursor_of_the_other_order_before_reading_anything() {
        String newestFirst = new BookingCursor(NOW, "ABC234").encode();
        String soonestFirst = new UpcomingCursor(LocalDate.parse("2026-12-01"), "ABC234").encode();

        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, newestFirst, "upcoming"));
        assertThatExceptionOfType(com.skyhub.booking.domain.InvalidBookingException.class).isThrownBy(() -> service.list(7L, soonestFirst, null));
        org.mockito.Mockito.verifyNoInteractions(bookings);
    }
}
