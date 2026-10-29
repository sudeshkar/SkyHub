package com.skyhub.booking.domain;

import java.security.SecureRandom;

/** Six characters from 32 symbols (no 0/O or 1/I): about 1.07 billion combinations. */
public final class RandomLocatorGenerator implements LocatorGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        char[] locator = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            locator[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(locator);
    }
}
