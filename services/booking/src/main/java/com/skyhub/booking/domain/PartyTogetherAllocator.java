package com.skyhub.booking.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps a travelling party as close together as the free seats allow, in this order of preference: seats side by side in
 * one row; failing that, seats in one row; failing that, the nearest seats from the front.
 *
 * <p>Cost: one sort of the free seats, O(n log n), then a single pass with a window over each row, O(n). Nothing is
 * rescanned per passenger, so a full wide-body cabin is as cheap as a small one.
 */
public final class PartyTogetherAllocator implements SeatAllocator {

    private static final Comparator<SeatInventory> FRONT_TO_BACK =
            Comparator.comparingInt(SeatInventory::getRowNumber).thenComparing(SeatInventory::getLetter);

    @Override
    public List<SeatInventory> allocate(List<SeatInventory> free, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("A party has at least one passenger");
        }
        if (free.size() < count) {
            throw new SeatUnavailableException("Only " + free.size() + " free seats are left, " + count + " are needed");
        }
        List<SeatInventory> ordered = new ArrayList<>(free);
        ordered.sort(FRONT_TO_BACK);

        List<SeatInventory> sameRow = null;
        int start = 0;
        while (start < ordered.size()) {
            int end = start;
            int row = ordered.get(start).getRowNumber();
            while (end < ordered.size() && ordered.get(end).getRowNumber() == row) {
                end++;
            }
            List<SeatInventory> thisRow = ordered.subList(start, end);
            List<SeatInventory> block = adjacentBlock(thisRow, count);
            if (block != null) {
                return List.copyOf(block);                  // the frontmost row with a block wins
            }
            if (sameRow == null && thisRow.size() >= count) {
                sameRow = thisRow.subList(0, count);        // remember the first row that could hold everyone
            }
            start = end;
        }
        return List.copyOf(sameRow != null ? sameRow : ordered.subList(0, count));
    }

    /** The first run of {@code count} seats whose letters follow one another, or null. One pass with a moving window. */
    private static List<SeatInventory> adjacentBlock(List<SeatInventory> row, int count) {
        for (int i = 0; i + count <= row.size(); i++) {
            if (row.get(i + count - 1).getLetter().charAt(0) - row.get(i).getLetter().charAt(0) == count - 1) {
                return row.subList(i, i + count);   // sorted and distinct, so this span means the letters are consecutive
            }
        }
        return null;
    }
}
