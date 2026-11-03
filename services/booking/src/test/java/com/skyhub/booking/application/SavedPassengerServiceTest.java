package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.SavedPassengerLimitException;
import com.skyhub.booking.domain.SavedPassengerNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The names a traveller keeps on their account to fill a booking with (ADR-0078). */
class SavedPassengerServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private SavedPassengerService service;

    @BeforeEach
    void setUp() {
        service = new SavedPassengerService(new FakeSavedPassengers(), new SavedPassengerMetrics(registry));
    }

    private double count(String outcome) {
        var counter = registry.find("skyhub.saved_passengers").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void should_save_a_name_trimmed_and_list_it_oldest_first() {
        service.add(7L, "  Priya ", " Perera  ");
        service.add(7L, "Raj", "Perera");

        assertThat(service.list(7L)).extracting(SavedPassengers.Saved::firstName).containsExactly("Priya", "Raj");
        assertThat(service.list(7L).getFirst().lastName()).isEqualTo("Perera");
        assertThat(count("added")).isEqualTo(2);
    }

    @Test
    void should_say_whether_the_name_was_new_and_answer_a_repeat_with_the_one_already_kept() {
        var first = service.add(7L, "Priya", "Perera");
        var again = service.add(7L, "  priya", "PERERA ");   // the same person, typed differently

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.passenger().id()).isEqualTo(first.passenger().id());
        assertThat(service.list(7L)).hasSize(1);   // saving twice never uses up a second place
        assertThat(count("already_saved")).isEqualTo(1);
    }

    @Test
    void should_refuse_a_blank_or_too_long_name_before_saving_anything() {
        for (String[] bad : new String[][] {{"", "Perera"}, {"Priya", "   "}, {null, "Perera"}, {"Priya", null}, {"x".repeat(51), "Perera"}, {"Priya", "x".repeat(51)}}) {
            assertThatThrownBy(() -> service.add(7L, bad[0], bad[1])).isInstanceOf(InvalidBookingException.class);
        }
        assertThat(service.list(7L)).isEmpty();
    }

    @Test
    void should_stop_at_ten_names_and_say_so_without_changing_anything() {
        for (int i = 0; i < SavedPassengerService.LIMIT; i++) {
            service.add(7L, "First" + i, "Perera");
        }

        assertThatThrownBy(() -> service.add(7L, "OneTooMany", "Perera")).isInstanceOf(SavedPassengerLimitException.class);
        assertThat(service.list(7L)).hasSize(10);
        assertThat(count("full")).isEqualTo(1);
        service.add(7L, "First3", "Perera");   // a name already kept is still answered, even when the places are full
    }

    @Test
    void should_keep_one_account_s_names_away_from_every_other() {
        var mine = service.add(7L, "Priya", "Perera").passenger();
        service.add(8L, "Anil", "Silva");

        assertThat(service.list(7L)).extracting(SavedPassengers.Saved::firstName).containsExactly("Priya");
        assertThatThrownBy(() -> service.remove(8L, mine.id())).isInstanceOf(SavedPassengerNotFoundException.class);   // not found, not forbidden
        assertThat(service.list(7L)).hasSize(1);
    }

    @Test
    void should_remove_a_name_once_and_say_not_found_the_second_time() {
        long id = service.add(7L, "Priya", "Perera").passenger().id();

        service.remove(7L, id);

        assertThat(service.list(7L)).isEmpty();
        assertThatThrownBy(() -> service.remove(7L, id)).isInstanceOf(SavedPassengerNotFoundException.class);
        assertThat(count("removed")).isEqualTo(1);
        assertThat(count("not_found")).isEqualTo(1);
    }
}
