package com.skyhub.booking.security;

import com.skyhub.booking.application.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Adapter: BCrypt, which salts every hash and is deliberately slow (cost 12 by default). BCrypt reads only the
 * first 72 bytes of a password and this encoder refuses anything longer, which is why the password policy caps passwords
 * at 72 bytes (ADR-0048): a longer one cannot be stored.
 */
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }
}
