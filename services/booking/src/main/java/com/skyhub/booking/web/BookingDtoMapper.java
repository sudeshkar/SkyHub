package com.skyhub.booking.web;

import com.skyhub.booking.api.model.CabinDto;
import com.skyhub.booking.api.model.CreatePnrRequestDto;
import com.skyhub.booking.api.model.PassengerDto;
import com.skyhub.booking.api.model.PnrDto;
import com.skyhub.booking.api.model.PnrStatusDto;
import com.skyhub.booking.api.model.SeatDto;
import com.skyhub.booking.api.model.SeatMapDto;
import com.skyhub.booking.api.model.SeatStatusDto;
import com.skyhub.booking.api.model.SegmentDto;
import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.application.PassengerCommand;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.application.view.SeatView;
import com.skyhub.booking.domain.PnrSegment;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/** Translates between API DTOs (the public contract) and application commands and views. */
final class BookingDtoMapper {

    private BookingDtoMapper() {
    }

    static CreatePnrCommand toCommand(CreatePnrRequestDto request) {
        return new CreatePnrCommand(
                request.getContactEmail(),
                request.getSegments().stream()
                        .map(s -> new PnrSegment(s.getFlightNumber(), s.getDepartureDate()))
                        .toList(),
                request.getPassengers().stream()
                        .map(p -> new PassengerCommand(p.getFirstName(), p.getLastName(), List.copyOf(p.getSeats())))
                        .toList());
    }

    static PnrDto toDto(PnrView pnr) {
        return new PnrDto(
                pnr.locator(),
                PnrStatusDto.valueOf(pnr.status().name()),
                pnr.maskedContactEmail(),
                pnr.segments().stream().map(s -> new SegmentDto(s.flightNumber(), s.departureDate())).toList(),
                pnr.passengers().stream()
                        .map(p -> new PassengerDto(p.firstName(), p.lastName(), p.seats()))
                        .toList(),
                pnr.createdAt().atOffset(ZoneOffset.UTC));
    }

    static SeatMapDto toDto(String flightNumber, LocalDate departureDate, List<SeatView> seats) {
        return new SeatMapDto(flightNumber, departureDate, seats.stream()
                .map(s -> new SeatDto(s.seatNumber(), s.row(), s.letter(),
                        CabinDto.valueOf(s.cabin().name()), SeatStatusDto.valueOf(s.status().name())))
                .toList());
    }
}
