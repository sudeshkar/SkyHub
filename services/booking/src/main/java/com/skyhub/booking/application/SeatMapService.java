package com.skyhub.booking.application;

import com.skyhub.booking.application.view.SeatView;
import com.skyhub.booking.persistence.SeatInventoryRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SeatMapService {

    private final SeatInventoryRepository seats;
    private final SeatInventoryInitializer inventory;

    public SeatMapService(SeatInventoryRepository seats, SeatInventoryInitializer inventory) {
        this.seats = seats;
        this.inventory = inventory;
    }

    public List<SeatView> seatMap(String flightNumber, LocalDate departureDate) {
        inventory.ensureInventory(flightNumber, departureDate);
        return seats.findByFlightNumberAndDepartureDateOrderByRowNumberAscLetterAsc(flightNumber, departureDate)
                .stream()
                .map(SeatView::of)
                .toList();
    }
}
