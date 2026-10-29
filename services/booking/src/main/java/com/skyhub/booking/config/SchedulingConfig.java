package com.skyhub.booking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Kept off the main class so slice tests don't start schedulers. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
