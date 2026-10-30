package com.skyhub.booking.application;

/**
 * Port: turns a password into a hash that can be stored, and checks a password against one. The application owns
 * this interface; the BCrypt implementation lives in the security package, like the token issuers.
 */
public interface PasswordHasher {

    String hash(String password);

    boolean matches(String password, String hash);
}
