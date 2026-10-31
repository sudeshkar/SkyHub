package com.skyhub.booking.web;

import com.skyhub.booking.api.MyBookingsApi;
import com.skyhub.booking.api.model.BookingSummaryDto;
import com.skyhub.booking.api.model.ManageSessionDto;
import com.skyhub.booking.api.model.MyBookingsDto;
import com.skyhub.booking.api.model.PnrStatusDto;
import com.skyhub.booking.api.model.SegmentDto;
import com.skyhub.booking.application.BookingList;
import com.skyhub.booking.application.MyBookingsService;
import com.skyhub.booking.application.view.BookingSummary;
import java.time.ZoneOffset;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Both endpoints need an account token, checked in SecurityConfig before this runs; the account is the token's subject, never
 * something the caller can name. Responses name bookings or carry a credential, so they are never cached.
 */
@RestController
public class MyBookingsController implements MyBookingsApi {

    private final MyBookingsService myBookings;

    public MyBookingsController(MyBookingsService myBookings) {
        this.myBookings = myBookings;
    }

    @Override
    public ResponseEntity<MyBookingsDto> listMyBookings(String cursor, String when) {
        BookingList list = myBookings.list(accountId(), cursor, when);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MyBookingsDto(list.bookings().stream().map(MyBookingsController::toDto).toList(), list.truncated())
                        .nextCursor(list.nextCursor()));
    }

    @Override
    public ResponseEntity<ManageSessionDto> openMyBookingSession(String locator) {
        ManageSessionDto body = BookingDtoMapper.toDto(myBookings.openSession(accountId(), locator));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    private static long accountId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private static BookingSummaryDto toDto(BookingSummary summary) {
        return new BookingSummaryDto(summary.locator(), PnrStatusDto.valueOf(summary.status().name()),
                summary.segments().stream().map(s -> new SegmentDto(s.flightNumber(), s.departureDate())).toList(),
                summary.passengerCount(), summary.createdAt().atOffset(ZoneOffset.UTC));
    }
}
