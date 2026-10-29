package com.skyhub.booking.domain;

import java.util.ArrayList;
import java.util.List;

/** Cabin layout. Phase 1 has one aircraft configuration for every flight. */
public final class SeatLayout {

    private final List<SeatSpec> seats;

    private SeatLayout(List<SeatSpec> seats) {
        this.seats = List.copyOf(seats);
    }

    /** Business rows 1–3 (A C D F), economy rows 10–35 (A–F). */
    public static SeatLayout standard() {
        List<SeatSpec> seats = new ArrayList<>();
        addRows(seats, 1, 3, List.of("A", "C", "D", "F"), Cabin.BUSINESS);
        addRows(seats, 10, 35, List.of("A", "B", "C", "D", "E", "F"), Cabin.ECONOMY);
        return new SeatLayout(seats);
    }

    private static void addRows(List<SeatSpec> seats, int firstRow, int lastRow, List<String> letters, Cabin cabin) {
        for (int row = firstRow; row <= lastRow; row++) {
            for (String letter : letters) {
                seats.add(new SeatSpec(row, letter, cabin));
            }
        }
    }

    public List<SeatSpec> seats() {
        return seats;
    }
}
