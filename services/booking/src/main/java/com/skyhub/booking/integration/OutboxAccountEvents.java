package com.skyhub.booking.integration;

import com.skyhub.booking.application.AccountEvents;
import com.skyhub.booking.application.LinkTokenSealer;
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
 * Adapter: records "send this person a link" in the outbox (ADR-0033). The payload holds a one-time token, which goes in sealed
 * (ADR-0046) because the relay publishes the payload to the topic in the clear; like every payload it is also encrypted at rest; the shape is api-specs/events/account-password-link.v1.example.json.
 */
@Component
public class OutboxAccountEvents implements AccountEvents {

    static final String PASSWORD_LINK = "account.password-link";
    static final String EMAIL_CHANGE_LINK = "account.email-change-link";
    static final String EMAIL_CHANGED = "account.email-changed";

    private final OutboxEventRepository outbox;
    private final PiiCipher cipher;
    private final JsonMapper json;
    private final Clock clock;
    private final LinkTokenSealer sealer;
    private final Supplier<UUID> ids;

    @Autowired
    public OutboxAccountEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock, LinkTokenSealer sealer) {
        this(outbox, cipher, json, clock, sealer, UUID::randomUUID);
    }

    OutboxAccountEvents(OutboxEventRepository outbox, PiiCipher cipher, JsonMapper json, Clock clock, LinkTokenSealer sealer,
                        Supplier<UUID> ids) {
        this.sealer = sealer;
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
        event.put("sealedToken", sealer.seal(token, PASSWORD_LINK, email.value()));   // never the token itself (ADR-0046)
        event.put("expiresAt", expiresAt.toString());
        outbox.save(OutboxEvent.pending(id, "account-" + accountId, PASSWORD_LINK,
                cipher.encrypt(json.writeValueAsString(event)), clock.instant()));
    }

    /** The shape is api-specs/events/account-email-changed.v1.example.json. {@code contactEmail} is the OLD address. */
    @Override
    public void emailChanged(long accountId, ContactEmail oldEmail, ContactEmail newEmail) {
        UUID id = ids.get();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id.toString());
        event.put("type", EMAIL_CHANGED);
        event.put("version", 1);
        event.put("occurredAt", clock.instant().toString());
        event.put("contactEmail", oldEmail.value());
        event.put("newEmailMasked", newEmail.masked());
        outbox.save(OutboxEvent.pending(id, "account-" + accountId, EMAIL_CHANGED,
                cipher.encrypt(json.writeValueAsString(event)), clock.instant()));
    }

    /** The shape is api-specs/events/account-email-change-link.v1.example.json. {@code contactEmail} is the NEW address. */
    @Override
    public void emailChangeRequested(long accountId, ContactEmail newEmail, String token, Instant expiresAt) {
        UUID id = ids.get();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id.toString());
        event.put("type", EMAIL_CHANGE_LINK);
        event.put("version", 1);
        event.put("occurredAt", clock.instant().toString());
        event.put("contactEmail", newEmail.value());
        event.put("sealedToken", sealer.seal(token, EMAIL_CHANGE_LINK, newEmail.value()));   // never the token itself (ADR-0046)
        event.put("expiresAt", expiresAt.toString());
        outbox.save(OutboxEvent.pending(id, "account-" + accountId, EMAIL_CHANGE_LINK,
                cipher.encrypt(json.writeValueAsString(event)), clock.instant()));
    }
}
