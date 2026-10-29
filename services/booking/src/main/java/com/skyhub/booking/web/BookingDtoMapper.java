package com.skyhub.booking.web;

import com.skyhub.booking.api.model.CabinDto;
import com.skyhub.booking.api.model.CreatePnrRequestDto;
import com.skyhub.booking.api.model.BoardingPassDto;
import com.skyhub.booking.api.model.BoardingPassesDto;
import com.skyhub.booking.api.model.CreateHoldRequestDto;
import com.skyhub.booking.api.model.HoldDto;
import com.skyhub.booking.api.model.HoldItemDto;
import com.skyhub.booking.api.model.ManageSessionDto;
import com.skyhub.booking.api.model.PassengerDto;
import com.skyhub.booking.api.model.PnrDto;
import com.skyhub.booking.api.model.PnrStatusDto;
import com.skyhub.booking.api.model.SeatChangeDto;
import com.skyhub.booking.api.model.SeatDto;
import com.skyhub.booking.api.model.SeatMapDto;
import com.skyhub.booking.api.model.SeatStatusDto;
import com.skyhub.booking.api.model.SegmentDto;
import com.skyhub.booking.application.CheckInResult;
import com.skyhub.booking.application.CreateHoldCommand;
import com.skyhub.booking.application.CreatePnrCommand;
import com.skyhub.booking.application.HeldSeat;
import com.skyhub.booking.application.HoldView;
import com.skyhub.booking.application.ManageSession;
import com.skyhub.booking.application.PassengerCommand;
import com.skyhub.booking.application.SeatChangeCommand;
import com.skyhub.booking.application.view.PnrView;
import com.skyhub.booking.application.view.SeatView;
import com.skyhub.booking.domain.PnrSegment;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

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
                        .toList(),
                request.getHoldId() == null ? null : request.getHoldId().toString());
    }

    static CreateHoldCommand toCommand(CreateHoldRequestDto request) {
        return new CreateHoldCommand(request.getItems().stream()
                .map(i -> new HeldSeat(i.getFlightNumber(), i.getDepartureDate(), i.getSeatNumber()))
                .toList());
    }

    static BoardingPassesDto toDto(CheckInResult result) {
        return new BoardingPassesDto(result.passes().stream()
                .map(p -> new BoardingPassDto(p.passengerName(), p.locator(), p.flightNumber(), p.departureDate(),
                        p.seatNumber(), p.sequence(), p.barcode()))
                .toList());
    }

    static HoldDto toDto(HoldView hold) {
        return new HoldDto(UUID.fromString(hold.holdId()), hold.expiresAt().atOffset(ZoneOffset.UTC),
                hold.items().stream()
                        .map(i -> new HoldItemDto(i.flightNumber(), i.departureDate(), i.seatNumber()))
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

    static SeatChangeCommand toCommand(SeatChangeDto request) {
        return new SeatChangeCommand(request.getPassengerIndex(), request.getSegmentIndex(), request.getSeatNumber());
    }

    static ManageSessionDto toDto(ManageSession session) {
        return new ManageSessionDto(session.token().value(), "Bearer",
                session.token().expiresAt().atOffset(ZoneOffset.UTC), toDto(session.pnr()));
    }

    static SeatMapDto toDto(String flightNumber, LocalDate departureDate, List<SeatView> seats) {
        return new SeatMapDto(flightNumber, departureDate, seats.stream()
                .map(s -> new SeatDto(s.seatNumber(), s.row(), s.letter(),
                        CabinDto.valueOf(s.cabin().name()), SeatStatusDto.valueOf(s.status().name())))
                .toList());
    }
}
