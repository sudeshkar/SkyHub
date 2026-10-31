package com.skyhub.booking.integration;

import com.skyhub.booking.application.LinkTokenSealer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adapter: AES-256-GCM, in the format the system uses for encrypted values: {@code v1:} and Base64 of the random 12-byte IV, the
 * ciphertext and the 16-byte tag (ADR-0046). The event type and the address are authenticated but not encrypted (GCM additional data),
 * so the seal opens only for the pair it was made for. The key is shared with the notification service and with nothing else, and
 * is not the personal-data key; the service refuses to start without a proper one.
 */
@Component
public class AesGcmLinkTokenSealer implements LinkTokenSealer {

    private static final String VERSION = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmLinkTokenSealer(@Value("${skyhub.security.link-token-key:}") String base64Key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("skyhub.security.link-token-key must be Base64 (set the SKYHUB_LINK_TOKEN_KEY environment variable)", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "skyhub.security.link-token-key must be a Base64 256-bit key (set the SKYHUB_LINK_TOKEN_KEY environment variable)");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String seal(String token, String eventType, String address) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            // Cipher instances are not thread-safe, so each call gets its own.
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD((eventType + "|" + address).getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Sealing the link token failed", e);
        }
    }
}
