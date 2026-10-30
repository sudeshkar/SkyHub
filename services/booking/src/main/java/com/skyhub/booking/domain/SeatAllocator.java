package com.skyhub.booking.domain;

import java.util.List;

/**
 * Strategy: chooses seats for a group from the seats that are free. Other ways of choosing (cheapest, aisle first,
 * keep a family in a window block) are new implementations; the rebooking use case never changes.
 */
public interface SeatAllocator {

    /**
     * @param free  seats that are free right now, in any order; not modified
     * @param count how many are needed, at least 1
     * @return exactly {@code count} of the given seats
     * @throws SeatUnavailableException when fewer than {@code count} are free
     */
    List<SeatInventory> allocate(List<SeatInventory> free, int count);
}
