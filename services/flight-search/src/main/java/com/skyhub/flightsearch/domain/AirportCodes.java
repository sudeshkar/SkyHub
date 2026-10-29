package com.skyhub.flightsearch.domain;

import java.time.Duration;
import java.util.regex.Pattern;

final class AirportCodes {

    private static final Pattern IATA_CODE = Pattern.compile("^[A-Z]{3}$");

    private AirportCodes() {
    }

    static String requireValid(String code) {
        if (code == null || !IATA_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid IATA airport code: " + code);
        }
        return code;
    }

    static Duration requirePositive(Duration duration, String what) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(what + " must be positive, was " + duration);
        }
        return duration;
    }
}
