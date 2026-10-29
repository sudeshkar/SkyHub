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

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
