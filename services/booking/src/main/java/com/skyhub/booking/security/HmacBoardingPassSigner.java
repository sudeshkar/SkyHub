package com.skyhub.booking.security;

import com.skyhub.booking.application.BoardingPassSigner;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 over the payload; the signature follows the last dot, base64url without padding. */
public final class HmacBoardingPassSigner implements BoardingPassSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKeySpec key;

    public HmacBoardingPassSigner(byte[] key) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("The boarding pass key must be at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key, ALGORITHM);
    }

    @Override
    public String sign(String payload) {
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }

    @Override
    public boolean verify(String signed) {
        int dot = signed.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            byte[] given = Base64.getUrlDecoder().decode(signed.substring(dot + 1));
            // Constant-time comparison: the check must not reveal how many bytes matched.
            return MessageDigest.isEqual(mac(signed.substring(0, dot)), given);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
