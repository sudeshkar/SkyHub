package com.skyhub.booking.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.skyhub.booking.application.AccountLoginFailedException;
import com.skyhub.booking.application.AccountTokenIssuer;
import com.skyhub.booking.application.BookingList;
import com.skyhub.booking.application.ManageSession;
import com.skyhub.booking.application.ManageToken;
import com.skyhub.booking.application.ManageTokenIssuer;
import com.skyhub.booking.application.MyBookingsService;
import com.skyhub.booking.application.StaffTokenIssuer;
import com.skyhub.booking.application.view.BookingSummary;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.domain.PnrNotFoundException;
import com.skyhub.booking.domain.PnrStatus;
import com.skyhub.booking.domain.StaffRole;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MyBookingsController.class)
@Import(WebSliceSecurity.class)
class MyBookingsControllerTest {

    private static final Instant CREATED = Instant.parse("2026-10-29T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountTokenIssuer accountTokens;

    @Autowired
    private ManageTokenIssuer manageTokens;

    @Autowired
    private StaffTokenIssuer staffTokens;

    @MockitoBean
    private MyBookingsService myBookings;

    private String account() {
        return "Bearer " + accountTokens.issue(7L).value();
    }

    @Test
    void should_list_the_accounts_bookings_without_names_seats_or_addresses_and_never_cache_them() throws Exception {
        when(myBookings.list(7L, null)).thenReturn(new BookingList(List.of(new BookingSummary("ABC234", PnrStatus.CONFIRMED,
                List.of(new PnrView.SegmentView("SK001", LocalDate.parse("2026-12-01"))), 2, CREATED)), "next-page-cursor"));

        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", account()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.nextCursor").value("next-page-cursor"))
                .andExpect(jsonPath("$.bookings[0].locator").value("ABC234"))
                .andExpect(jsonPath("$.bookings[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.bookings[0].segments[0].flightNumber").value("SK001"))
                .andExpect(jsonPath("$.bookings[0].passengerCount").value(2))
                .andExpect(jsonPath("$.bookings[0].passengers").doesNotExist())
                .andExpect(jsonPath("$.bookings[0].contactEmail").doesNotExist());
    }

    @Test
    void should_pass_the_cursor_on_and_say_there_is_no_next_page_on_the_last_one() throws Exception {
        when(myBookings.list(7L, "the-cursor")).thenReturn(new BookingList(List.of(), null));

        mvc.perform(get("/api/v1/accounts/me/bookings").param("cursor", "the-cursor").header("Authorization", account()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void should_answer_400_for_a_cursor_the_service_did_not_make() throws Exception {
        when(myBookings.list(7L, "forged")).thenThrow(new com.skyhub.booking.domain.InvalidBookingException("That page of bookings is not valid"));

        mvc.perform(get("/api/v1/accounts/me/bookings").param("cursor", "forged").header("Authorization", account()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("That page of bookings is not valid"));
    }

    @Test
    void should_refuse_an_absurdly_long_cursor_before_the_service_is_asked() throws Exception {
        mvc.perform(get("/api/v1/accounts/me/bookings").param("cursor", "x".repeat(500)).header("Authorization", account()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(myBookings);
    }

    @Test
    void should_open_one_of_my_bookings_with_201_and_a_manage_session() throws Exception {
        PnrView pnr = new PnrView("ABC234", PnrStatus.CONFIRMED, "p****@example.com",
                List.of(new PnrView.SegmentView("SK001", LocalDate.parse("2026-12-01"))),
                List.of(new PnrView.PassengerView("Priya", "Perera", List.of("11D"))), CREATED);
        when(myBookings.openSession(7L, "ABC234")).thenReturn(new ManageSession(new ManageToken("jwt", CREATED.plusSeconds(900)), pnr));

        mvc.perform(post("/api/v1/accounts/me/bookings/ABC234/sessions").header("Authorization", account()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("jwt"))
                .andExpect(jsonPath("$.pnr.locator").value("ABC234"));
    }

    @Test
    void should_answer_404_for_a_booking_that_is_not_mine() throws Exception {
        when(myBookings.openSession(7L, "ZZZZZZ")).thenThrow(new PnrNotFoundException("No booking matches this reference"));

        mvc.perform(post("/api/v1/accounts/me/bookings/ZZZZZZ/sessions").header("Authorization", account()))
                .andExpect(status().isNotFound());
        verify(myBookings).openSession(7L, "ZZZZZZ");
    }

    @Test
    void should_refuse_a_token_for_an_account_that_no_longer_exists() throws Exception {
        when(myBookings.list(7L, null)).thenThrow(new AccountLoginFailedException());

        mvc.perform(get("/api/v1/accounts/me/bookings").header("Authorization", account())).andExpect(status().isUnauthorized());
    }

    @Test
    void should_serve_only_a_holder_of_an_account_token() throws Exception {
        String manage = "Bearer " + manageTokens.issue("ABC234").value();
        String staff = "Bearer " + staffTokens.issue("priya.ops", StaffRole.ADMIN).value();

        String list = "/api/v1/accounts/me/bookings";
        mvc.perform(get(list)).andExpect(status().isUnauthorized());
        mvc.perform(get(list).header("Authorization", manage)).andExpect(status().isForbidden());
        mvc.perform(get(list).header("Authorization", staff)).andExpect(status().isForbidden());
        String open = "/api/v1/accounts/me/bookings/ABC234/sessions";
        mvc.perform(post(open)).andExpect(status().isUnauthorized());
        mvc.perform(post(open).header("Authorization", manage)).andExpect(status().isForbidden());
        mvc.perform(post(open).header("Authorization", staff)).andExpect(status().isForbidden());
        verifyNoInteractions(myBookings);
    }
}
