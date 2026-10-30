package com.skyhub.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PartyTogetherAllocatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);
    private final SeatAllocator allocator = new PartyTogetherAllocator();

    private static SeatInventory seat(int row, String letter) {
        return SeatInventory.available("SK001", DATE, new SeatSpec(row, letter, Cabin.ECONOMY));
    }

    private static List<SeatInventory> free(String... seats) {
        List<SeatInventory> list = new ArrayList<>();
        for (String s : seats) {
            list.add(seat(Integer.parseInt(s.substring(0, s.length() - 1)), s.substring(s.length() - 1)));
        }
        return list;
    }

    private static List<String> numbers(List<SeatInventory> seats) {
        return seats.stream().map(SeatInventory::getSeatNumber).toList();
    }

    @Test
    void should_seat_a_party_side_by_side_when_a_row_has_room() {
        List<SeatInventory> chosen = allocator.allocate(free("9A", "9C", "9E", "9F", "10A", "10B", "10C"), 3);

        assertThat(numbers(chosen)).containsExactly("10A", "10B", "10C");
    }

    @Test
    void should_use_the_frontmost_row_that_has_a_block() {
        List<SeatInventory> chosen = allocator.allocate(free("20D", "20E", "12A", "12B", "30A", "30B"), 2);

        assertThat(numbers(chosen)).containsExactly("12A", "12B");
    }

    @Test
    void should_keep_a_party_in_one_row_when_no_two_seats_are_adjacent() {
        List<SeatInventory> chosen = allocator.allocate(free("5A", "5C", "5E", "6B"), 3);

        assertThat(numbers(chosen)).containsExactly("5A", "5C", "5E");
    }

    @Test
    void should_spread_over_nearby_rows_only_when_no_row_can_hold_the_party() {
        List<SeatInventory> chosen = allocator.allocate(free("7C", "5A", "6B"), 3);

        assertThat(numbers(chosen)).containsExactly("5A", "6B", "7C");
    }

    @Test
    void should_refuse_when_there_are_fewer_free_seats_than_passengers_and_change_nothing() {
        List<SeatInventory> candidates = free("5A", "5B");

        assertThatExceptionOfType(SeatUnavailableException.class).isThrownBy(() -> allocator.allocate(candidates, 3))
                .withMessageContaining("seats");
        assertThat(candidates).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SeatStatus.AVAILABLE));
    }

    @Test
    void should_not_reorder_or_change_the_list_it_is_given() {
        List<SeatInventory> candidates = free("9F", "3A", "3B");
        List<String> before = numbers(candidates);

        allocator.allocate(candidates, 2);

        assertThat(numbers(candidates)).isEqualTo(before);
    }

    @Test
    void should_refuse_a_party_of_nobody() {
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.allocate(free("5A"), 0));
    }

    @Test
    void should_give_the_same_answer_whatever_order_the_seats_arrive_in() {
        List<SeatInventory> shuffled = free("10A", "10B", "10C", "11A", "11B", "9F");
        Collections.shuffle(shuffled, new Random(7));

        assertThat(numbers(allocator.allocate(shuffled, 2))).containsExactly("10A", "10B");
    }

    @Test
    void should_stay_fast_on_a_full_widebody_cabin() {
        List<SeatInventory> cabin = new ArrayList<>();
        for (int row = 1; row <= 1200; row++) {   // 12,000 seats, far bigger than any real aircraft
            for (String letter : List.of("A", "B", "C", "D", "E", "F", "G", "H", "J", "K")) {
                if ((row * 7 + letter.charAt(0)) % 3 != 0) { // about two thirds of the seats are free
                    cabin.add(seat(row, letter));
                }
            }
        }

        long started = System.nanoTime();
        List<SeatInventory> chosen = allocator.allocate(cabin, 9);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(chosen).hasSize(9);
        assertThat(millis).isLessThan(1_000);
    }
}
