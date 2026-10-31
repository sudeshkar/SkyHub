package com.skyhub.notification.integration;

import com.skyhub.notification.application.LinkTokenOpener;
import com.skyhub.notification.domain.InvalidEventException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Adapter: opens what the booking service sealed with AES-256-GCM, {@code v1:} and Base64 of the 12-byte IV, the ciphertext and the
 * 16-byte tag, bound to the event type and address (ADR-0046). The key is shared with the booking service and nothing else.
 *
 * <p>No key is not an error at start-up, because booking emails do not use it and should still go out: it is said once in the log, and
 * every link event is then refused, so it is retried and parked rather than sent with a token that could not be checked. A key that is
 * set but wrong is a mistake, and stops the service from starting.
 */
@Component
public class AesGcmLinkTokenOpener implements LinkTokenOpener {

    private static final Logger log = LoggerFactory.getLogger(AesGcmLinkTokenOpener.class);
    private static final String VERSION = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Null when no key was configured. */
    private final SecretKey key;

    public AesGcmLinkTokenOpener(@Value("${skyhub.security.link-token-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            log.atWarn().log("SKYHUB_LINK_TOKEN_KEY is not set: sign-up, reset and address-change links will be refused and parked; booking emails are unaffected");
            return;
        }
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
    public String open(String sealedToken, String eventType, String address) {
        if (key == null) {
            throw new InvalidEventException("Link tokens cannot be opened: SKYHUB_LINK_TOKEN_KEY is not set on the notification service");
        }
        if (sealedToken == null || !sealedToken.startsWith(VERSION)) {
            throw new InvalidEventException("The link token is not sealed in a format this service knows");
        }
        try {
            byte[] packed = Base64.getDecoder().decode(sealedToken.substring(VERSION.length()));
            if (packed.length < IV_BYTES + TAG_BITS / 8) {
                throw new InvalidEventException("The link token is too short to be sealed");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES));
            cipher.updateAAD((eventType + "|" + address).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(packed, IV_BYTES, packed.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Neither the sealed value nor the address goes in the message: it ends up in logs and in the parked-message listing.
            throw new InvalidEventException("The link token could not be opened: wrong key, changed value, or not made for this event", e);
        }
    }
}
