package com.skyhub.booking.application.view;

import com.skyhub.booking.domain.Pnr;
import com.skyhub.booking.domain.PnrSegment;
import com.skyhub.booking.domain.PnrStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read model returned by the application layer. Entities never leave it, so the web layer cannot
 * leak persistence details or lazily load data by accident. Personal data leaves only in masked form.
 */
public record PnrView(
        String locator,
        PnrStatus status,
        String maskedContactEmail,
        List<SegmentView> segments,
        List<PassengerView> passengers,
        Instant createdAt) {

    public record SegmentView(String flightNumber, LocalDate departureDate) {
    }

    public record PassengerView(String firstName, String lastName, List<String> seats) {
    }

    public static PnrView of(Pnr pnr) {
        return new PnrView(
                pnr.getLocator(),
                pnr.getStatus(),
                pnr.getContactEmail().masked(),
                pnr.getSegments().stream().map(PnrView::segment).toList(),
                pnr.getPassengers().stream()
                        .map(p -> new PassengerView(p.getFirstName(), p.getLastName(), p.getSeats()))
                        .toList(),
                pnr.getCreatedAt());
    }

    private static SegmentView segment(PnrSegment segment) {
        return new SegmentView(segment.flightNumber(), segment.departureDate());
    }
}
