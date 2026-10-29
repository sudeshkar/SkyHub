package com.skyhub.flightsearch.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.skyhub.flightsearch.PostgresTestcontainer;
import com.skyhub.flightsearch.domain.FlightSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/** Runs Flyway against real PostgreSQL; Hibernate's ddl-auto=validate fails the context if entities drift. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainer.class)
class FlightScheduleRepositoryTest {

    @Autowired
    private FlightScheduleRepository repository;

    @Test
    void should_load_seed_timetable() {
        assertThat(repository.count()).isEqualTo(10);
    }

    @Test
    void should_find_schedules_by_origin() {
        assertThat(repository.findByOrigin("DWC"))
                .extracting(FlightSchedule::getFlightNumber)
                .containsExactlyInAnyOrder("SK901", "SK411");
    }

    @Test
    void should_find_schedules_by_destination() {
        assertThat(repository.findByDestination("JFK"))
                .extracting(FlightSchedule::getFlightNumber)
                .containsExactly("SK901");
    }
}
