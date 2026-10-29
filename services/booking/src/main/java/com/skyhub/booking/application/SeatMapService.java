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
    private final SeatMapCache cache;

    public SeatMapService(SeatInventoryRepository seats, SeatInventoryInitializer inventory, SeatMapCache cache) {
        this.seats = seats;
        this.inventory = inventory;
        this.cache = cache;
    }

    public List<SeatView> seatMap(String flightNumber, LocalDate departureDate) {
        return cache.get(flightNumber, departureDate, () -> {
            inventory.ensureInventory(flightNumber, departureDate);
            return seats.findByFlightNumberAndDepartureDateOrderByRowNumberAscLetterAsc(flightNumber, departureDate)
                    .stream()
                    .map(SeatView::of)
                    .toList();
        });
    }
}
