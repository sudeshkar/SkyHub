package com.skyhub.booking.integration;

import com.skyhub.booking.application.AccountEvents;
import com.skyhub.booking.application.PasswordLinkPurpose;
import com.skyhub.booking.domain.ContactEmail;
import com.skyhub.booking.domain.OutboxEvent;
import com.skyhub.booking.persistence.OutboxEventRepository;
import com.skyhub.booking.persistence.PiiCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adapter: records "send this person a link" in the outbox (ADR-0033). The payload holds a one-time token, so like every
 * payload it is encrypted at rest; the shape is api-specs/events/account-password-link.v1.example.json.
 */
@Component
public class OutboxAccountEvents implements AccountEvents {

    static final String PASSWORD_LINK = "account.password-link";

    private final OutboxEventRepository outbox;
    private final PiiCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public OutboxAccountEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock) {
        this(outbox, cipher, json, clock, UUID::randomUUID);
    }

    OutboxAccountEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock, Supplier<UUID> ids) {
        this.outbox = outbox;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    public void passwordLinkRequested(long accountId, ContactEmail email, String token, PasswordLinkPurpose purpose, Instant expiresAt) {
        UUID id = ids.get();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id.toString());
        event.put("type", PASSWORD_LINK);
        event.put("version", 1);
        event.put("occurredAt", clock.instant().toString());
        event.put("contactEmail", email.value());
        event.put("purpose", purpose.name());
        event.put("token", token);
        event.put("expiresAt", expiresAt.toString());
        outbox.save(OutboxEvent.pending(id, "account-" + accountId, PASSWORD_LINK,
                cipher.encrypt(json.writeValueAsString(event)), clock.instant()));
    }
}
