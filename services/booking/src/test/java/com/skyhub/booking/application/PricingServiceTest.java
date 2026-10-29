package com.skyhub.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.skyhub.booking.domain.FlightNotFoundException;
import com.skyhub.booking.domain.InvalidBookingException;
import com.skyhub.booking.domain.Money;
import com.skyhub.booking.domain.PnrSegment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PricingServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final Instant LEAVES = Instant.parse("2026-11-02T10:00:00Z");

    private final Map<String, Money> fares = Map.of(
            "SK501", Money.of("310.50", "USD"),
            "SK901", Money.of("120.00", "USD"),
            "AE100", Money.of("500.00", "AED"));
    private final PricingService pricing = new PricingService(
            (flight, date) -> Optional.ofNullable(fares.get(flight)).map(fare -> new FlightInfo(LEAVES, fare)));

    @Test
    void should_add_the_fare_of_every_flight_for_every_passenger() {
        Money price = pricing.quote(List.of(new PnrSegment("SK501", DATE), new PnrSegment("SK901", DATE)), 2);

        assertThat(price).isEqualTo(Money.of("861.00", "USD")); // (310.50 + 120.00) * 2
    }

    @Test
    void should_refuse_a_flight_that_does_not_operate() {
        assertThatExceptionOfType(FlightNotFoundException.class)
                .isThrownBy(() -> pricing.quote(List.of(new PnrSegment("SK999", DATE)), 1))
                .withMessageContaining("SK999");
    }

    @Test
    void should_refuse_a_trip_priced_in_two_currencies() {
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> pricing.quote(List.of(new PnrSegment("SK501", DATE), new PnrSegment("AE100", DATE)), 1))
                .withMessageContaining("currenc");
    }

    @Test
    void should_refuse_a_trip_with_no_flights_or_no_passengers() {
        assertThatExceptionOfType(InvalidBookingException.class).isThrownBy(() -> pricing.quote(List.of(), 1));
        assertThatExceptionOfType(InvalidBookingException.class)
                .isThrownBy(() -> pricing.quote(List.of(new PnrSegment("SK501", DATE)), 0));
    }
}
