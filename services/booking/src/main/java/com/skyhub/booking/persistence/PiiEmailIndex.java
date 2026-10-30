package com.skyhub.booking.persistence;

import com.skyhub.booking.application.EmailIndex;
import org.springframework.stereotype.Component;

/** Adapter: the email index is the personal-data cipher's blind index. */
@Component
class PiiEmailIndex implements EmailIndex {

    private final PiiCipher cipher;

    PiiEmailIndex(PiiCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String of(String email) {
        return cipher.blindIndex(email);
    }
}
