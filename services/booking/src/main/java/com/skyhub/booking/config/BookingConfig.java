package com.skyhub.booking.config;

import com.skyhub.booking.domain.HoldIdGenerator;
import com.skyhub.booking.domain.LocatorGenerator;
import com.skyhub.booking.domain.RandomLocatorGenerator;
import com.skyhub.booking.domain.SeatLayout;
import com.skyhub.booking.domain.UuidHoldIdGenerator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BookingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    LocatorGenerator locatorGenerator() {
        return new RandomLocatorGenerator();
    }

    @Bean
    HoldIdGenerator holdIdGenerator() {
        return new UuidHoldIdGenerator();
    }

    @Bean
    SeatLayout seatLayout() {
        return SeatLayout.standard();
    }
}
