package com.skyhub.booking.config;

import com.skyhub.booking.application.BoardingPassSigner;
import com.skyhub.booking.domain.BoardingWindow;
import com.skyhub.booking.domain.BoardingWindows;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.security.HmacBoardingPassSigner;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CheckInConfig {

    @Bean
    CheckInWindow checkInWindow(@Value("${skyhub.booking.check-in.opens-before:PT24H}") Duration opensBefore,
                                @Value("${skyhub.booking.check-in.closes-before:PT60M}") Duration closesBefore) {
        return new CheckInWindow(opensBefore, closesBefore);
    }

    /**
     * Boarding opens 45 minutes and closes 10 minutes before departure unless set otherwise (ADR-0063), and a route can have a window of
     * its own (ADR-0077): {@code skyhub.booking.boarding.routes.DXB-JFK.opens-before} and {@code .closes-before}, either of which may be left
     * out to keep the standard one. A window that cannot work, or a route that is not two airport codes, stops the service starting.
     */
    @Bean
    BoardingWindows boardingWindows(org.springframework.core.env.Environment environment) {
        return boardingWindows(Binder.get(environment));
    }

    static BoardingWindows boardingWindows(Binder binder) {
        Duration opens = binder.bind("skyhub.booking.boarding.opens-before", Duration.class).orElse(Duration.ofMinutes(45));
        Duration closes = binder.bind("skyhub.booking.boarding.closes-before", Duration.class).orElse(Duration.ofMinutes(10));
        BoardingWindow standard = new BoardingWindow(opens, closes);
        Map<String, RouteWindow> routes = binder.bind("skyhub.booking.boarding.routes", Bindable.mapOf(String.class, RouteWindow.class))
                .orElse(Map.of());
        Map<String, BoardingWindow> windows = new LinkedHashMap<>();
        routes.forEach((route, settings) -> {
            try {
                windows.put(route, new BoardingWindow(settings.opensBefore() != null ? settings.opensBefore() : opens,
                        settings.closesBefore() != null ? settings.closesBefore() : closes));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("The boarding window for route " + route + " cannot work: " + e.getMessage(), e);
            }
        });
        try {
            return new BoardingWindows(standard, windows);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("skyhub.booking.boarding.routes: " + e.getMessage(), e);
        }
    }

    /** What a route's settings may say; whichever is left out keeps the standard value. */
    record RouteWindow(Duration opensBefore, Duration closesBefore) {
    }

    /** The service refuses to start without a proper key, rather than signing boarding passes with a weak one. */
    @Bean
    BoardingPassSigner boardingPassSigner(@Value("${skyhub.security.boarding-pass-key:}") String base64Key) {
        try {
            return new HmacBoardingPassSigner(Base64.getDecoder().decode(base64Key));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "skyhub.security.boarding-pass-key must be a Base64 key of at least 256 bits "
                            + "(set the SKYHUB_BOARDING_PASS_KEY environment variable)", e);
        }
    }
}
