package com.skyhub.flightsearch.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/** Kept off the main class so slice tests (@WebMvcTest, @DataJpaTest) don't require a CacheManager. */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {
}
