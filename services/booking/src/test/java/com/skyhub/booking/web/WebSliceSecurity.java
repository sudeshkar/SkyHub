package com.skyhub.booking.web;

import com.skyhub.booking.config.SecurityConfig;
import com.skyhub.booking.security.ManageTokenConfig;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** The real security rules and token plumbing for @WebMvcTest slices, which load no other configuration. */
@TestConfiguration(proxyBeanMethods = false)
@Import({SecurityConfig.class, ManageTokenConfig.class})
class WebSliceSecurity {

    /** The web slice has no database, so every account session counts as current; revocation is tested where the database is (ADR-0041). */
    @Bean
    com.skyhub.booking.application.AccountSessions accountSessions() {
        return (accountId, epoch) -> true;
    }

    @Bean
    com.skyhub.booking.application.AccountMetrics accountMetrics() {
        return new com.skyhub.booking.application.AccountMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** Likewise for booking sessions (ADR-0057): revocation is tested against the database in BookingSessionRevocationIntegrationTest. */
    @Bean
    com.skyhub.booking.application.BookingSessions bookingSessions() {
        return new com.skyhub.booking.application.BookingSessions() {
            @Override
            public int currentEpoch(String locator) {
                return 0;
            }

            @Override
            public boolean accepts(String locator, int epoch) {
                return true;
            }

            @Override
            public void endAll(String locator) {
            }
        };
    }

    @Bean
    com.skyhub.booking.application.ManageMetrics manageMetrics() {
        return new com.skyhub.booking.application.ManageMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
