package com.skyhub.flightsearch.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skyhub.flightsearch.domain.FlightSchedule;
import com.skyhub.flightsearch.persistence.FlightScheduleRepository;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TimetableCacheTest {

    private final FlightScheduleRepository repository = mock(FlightScheduleRepository.class);

    @Test
    void should_load_the_timetable_at_startup() {
        when(repository.findAll()).thenReturn(List.of(schedule("SK501")));

        assertThat(new TimetableCache(repository).current().flight("SK501")).isPresent();
    }

    @Test
    void should_publish_a_new_snapshot_on_refresh() {
        when(repository.findAll()).thenReturn(List.of(schedule("SK501")), List.of(schedule("SK501"), schedule("SK901")));
        var cache = new TimetableCache(repository);

        cache.refresh();

        assertThat(cache.current().size()).isEqualTo(2);
    }

    @Test
    void should_keep_serving_the_previous_snapshot_when_refresh_fails() {
        when(repository.findAll()).thenReturn(List.of(schedule("SK501")))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        var cache = new TimetableCache(repository);

        cache.refresh();

        assertThat(cache.current().flight("SK501")).isPresent();
    }

    private static FlightSchedule schedule(String number) {
        return new FlightSchedule(number, "CMB", "DXB", LocalTime.NOON, 60, "B777", BigDecimal.TEN, "USD", "1234567");
    }
}
