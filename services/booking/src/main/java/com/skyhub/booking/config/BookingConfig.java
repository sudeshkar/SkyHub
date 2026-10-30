package com.skyhub.booking.config;

import com.skyhub.booking.application.rebooking.RebookingRule;
import com.skyhub.booking.application.rebooking.RebookingRules;
import com.skyhub.booking.domain.HoldIdGenerator;
import com.skyhub.booking.domain.LocatorGenerator;
import com.skyhub.booking.domain.PartyTogetherAllocator;
import com.skyhub.booking.domain.RandomLocatorGenerator;
import com.skyhub.booking.domain.RefundPolicy;
import com.skyhub.booking.domain.SeatAllocator;
import com.skyhub.booking.domain.SeatLayout;
import com.skyhub.booking.domain.UuidHoldIdGenerator;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BookingConfig {

    /** How a rebooked party is seated (ADR-0023). Another strategy is another bean; the service does not change. */
    @Bean
    SeatAllocator seatAllocator() {
        return new PartyTogetherAllocator();
    }

    /** Every restriction on a rebooking. Add a rule to RebookingRules.defaults() and it applies. */
    @Bean
    List<RebookingRule> rebookingRules() {
        return RebookingRules.defaults();
    }

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
    RefundPolicy refundPolicy() {
        return new RefundPolicy();
    }

    @Bean
    SeatLayout seatLayout() {
        return SeatLayout.standard();
    }
}
