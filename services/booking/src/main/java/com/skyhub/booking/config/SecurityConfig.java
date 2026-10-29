package com.skyhub.booking.config;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny by default: only the endpoints in the OpenAPI contract are reachable. Guest booking needs no
 * login; the public surface is the Next.js server, which rate-limits per client. The service itself
 * is stateless, so there are no sessions or cookies, and CSRF protection does not apply.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(GET, "/api/v1/flights/*/departures/*/seats").permitAll()
                        .requestMatchers(POST, "/api/v1/pnrs").permitAll()
                        .requestMatchers(GET, "/api/v1/pnrs/*").permitAll()
                        // Actuator is only served on the management port, which is never exposed publicly.
                        .requestMatchers(GET, "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
