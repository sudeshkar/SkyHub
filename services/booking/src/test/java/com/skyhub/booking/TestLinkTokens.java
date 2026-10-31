package com.skyhub.booking;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * Opens the sealed token of a link event the way the notification service does (ADR-0046), with the test-only key, so tests can
 * follow the link a person would be sent. Written against the JDK directly rather than the code under test, so a mistake in the
 * sealer cannot hide itself by being undone by a matching mistake here.
 */
public final class TestLinkTokens {

    /** Test-only: 32 bytes of 0x02. The real key is the SKYHUB_LINK_TOKEN_KEY environment variable. */
    public static final String TEST_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

    private TestLinkTokens() {
    }

    /** The plain token inside a link event's sealed one. */
    public static String tokenOf(JsonNode event) {
        return open(event.get("sealedToken").asString(), event.get("type").asString(), event.get("contactEmail").asString());
    }

    /** The same for an event that carries a sealed token under another name, such as {@code sealedUndoToken} (ADR-0054). */
    public static String tokenOf(JsonNode event, String field) {
        return open(event.get(field).asString(), event.get("type").asString(), event.get("contactEmail").asString());
    }

    public static String open(String sealed, String eventType, String address) {
        try {
            byte[] packed = Base64.getDecoder().decode(sealed.substring("v1:".length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(TEST_KEY), "AES"), new GCMParameterSpec(128, packed, 0, 12));
            cipher.updateAAD((eventType + "|" + address).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The sealed token does not open for " + eventType + " and that address", e);
        }
    }
}
