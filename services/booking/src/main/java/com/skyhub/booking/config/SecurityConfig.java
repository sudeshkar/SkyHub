package com.skyhub.booking.config;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import com.skyhub.booking.application.PasswordHasher;
import com.skyhub.booking.security.BCryptPasswordHasher;
import com.skyhub.booking.security.OwnsBookingAuthorization;
import com.skyhub.booking.security.StaffAuthorization;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny by default: only the endpoints in the OpenAPI contract are reachable. Booking and seat maps are
 * open to guests (the public surface is the Next.js server, which rate-limits per client). Managing a
 * booking needs a short-lived token for that booking (ADR-0011): opening a session is the only way to
 * get one. The service is stateless, so there are no sessions or cookies and CSRF does not apply.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** BCrypt for staff passwords (ADR-0017). Strength 12 by default; tests turn it down to stay fast. */
    @Bean
    PasswordHasher passwordHasher(@Value("${skyhub.security.staff-password-strength:12}") int strength) {
        return new BCryptPasswordHasher(strength);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        OwnsBookingAuthorization ownsBooking = new OwnsBookingAuthorization();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(GET, "/api/v1/flights/*/departures/*/seats").permitAll()
                        .requestMatchers(POST, "/api/v1/holds").permitAll()
                        .requestMatchers(DELETE, "/api/v1/holds/{holdId}").permitAll()
                        .requestMatchers(POST, "/api/v1/quotes").permitAll()
                        .requestMatchers(POST, "/api/v1/pnrs").permitAll()
                        .requestMatchers(POST, "/api/v1/pnrs/{locator}/sessions").permitAll()
                        .requestMatchers(GET, "/api/v1/pnrs/{locator}").access(ownsBooking)
                        .requestMatchers(POST, "/api/v1/pnrs/{locator}/cancellation").access(ownsBooking)
                        .requestMatchers(PUT, "/api/v1/pnrs/{locator}/seat-assignments").access(ownsBooking)
                        .requestMatchers(POST, "/api/v1/accounts/password-links", "/api/v1/accounts/passwords",
                                "/api/v1/accounts/sessions").permitAll()
                        .requestMatchers(GET, "/api/v1/accounts/me").hasAuthority("SCOPE_account")
                        .requestMatchers(GET, "/api/v1/accounts/me/bookings").hasAuthority("SCOPE_account")
                        .requestMatchers(DELETE, "/api/v1/accounts/me/sessions").hasAuthority("SCOPE_account")
                        .requestMatchers(POST, "/api/v1/accounts/me/email-change", "/api/v1/accounts/me/email-change/confirmation")
                                .hasAuthority("SCOPE_account")
                        .requestMatchers(POST, "/api/v1/accounts/me/bookings/{locator}/sessions").hasAuthority("SCOPE_account")
                        .requestMatchers(POST, "/api/v1/ops/sessions").permitAll()
                        .requestMatchers(POST, "/api/v1/ops/staff").access(StaffAuthorization.createsStaff())
                        .requestMatchers(GET, "/api/v1/ops/flights/{flightNumber}/departures/{departureDate}/bookings")
                                .access(StaffAuthorization.viewsAffectedBookings())
                        .requestMatchers(POST, "/api/v1/ops/pnrs/{locator}/rebooking").access(StaffAuthorization.rebooks())
                        .requestMatchers(POST, "/api/v1/ops/rebookings").access(StaffAuthorization.rebooks())
                        .requestMatchers(POST, "/api/v1/ops/disruptions").access(StaffAuthorization.recordsDisruptions())
                        .requestMatchers(DELETE, "/api/v1/ops/disruptions/{flightNumber}/{departureDate}")
                                .access(StaffAuthorization.recordsDisruptions())
                        .requestMatchers(GET, "/api/v1/pnrs/{locator}/disruptions").access(ownsBooking)
                        .requestMatchers(POST, "/api/v1/pnrs/{locator}/check-ins").access(ownsBooking)
                        .requestMatchers(GET, "/api/v1/pnrs/{locator}/check-ins/{segmentIndex}").access(ownsBooking)
                        // Actuator is only served on the management port, which is never exposed publicly.
                        .requestMatchers(GET, "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
