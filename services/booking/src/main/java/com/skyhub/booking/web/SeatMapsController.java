package com.skyhub.booking.web;

import com.skyhub.booking.api.SeatMapsApi;
import com.skyhub.booking.api.model.SeatMapDto;
import com.skyhub.booking.application.SeatMapService;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeatMapsController implements SeatMapsApi {

    private final SeatMapService seatMapService;

    public SeatMapsController(SeatMapService seatMapService) {
        this.seatMapService = seatMapService;
    }

    @Override
    public ResponseEntity<SeatMapDto> getSeatMap(String flightNumber, LocalDate departureDate) {
        return ResponseEntity.ok(BookingDtoMapper.toDto(flightNumber, departureDate,
                seatMapService.seatMap(flightNumber, departureDate)));
    }
}
