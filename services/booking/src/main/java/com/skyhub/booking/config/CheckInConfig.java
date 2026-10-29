package com.skyhub.booking.config;

import com.skyhub.booking.application.BoardingPassSigner;
import com.skyhub.booking.domain.CheckInWindow;
import com.skyhub.booking.security.HmacBoardingPassSigner;
import java.time.Duration;
import java.util.Base64;
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
