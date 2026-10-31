# ADR-0046: Sealing one-time link tokens on the topic

**Status:** Accepted
**Date:** 2026-11-03

## Context

Two events carry a one-time secret across Kafka so the notification service can put it in a link: `account.password-link` (ADR-0033) and `account.email-change-link` (ADR-0039). The payload is encrypted in the booking database, but the outbox relay publishes it to the topic in the clear, where it sits for the topic's retention period. Anyone who can read that topic can take a token: for a sign-up link that is a pending account (they choose its password), for an email-change link it is the proof of the new mailbox. ADR-0033, ADR-0039 and ADR-0040 all list this as a negative. It is also the one place where a secret that the rest of the system is careful to store only as a hash is written down in readable form.

## Options considered

1. **Leave it** - the topic is not public, and tokens last an hour and work once. A reader of the topic in that window still wins.
2. **A reference in the event; the notification service fetches the token from booking** - nothing secret on the topic. It needs a new internal endpoint on booking, a credential for the notification service to call it, and a new runtime dependency on booking for sending an email, which until now works while booking is down (ADR-0019).
3. **Seal the token in the event** - the topic holds ciphertext that only the two services can read, using a key they share. No new endpoint, no new dependency at send time, and the event keeps its shape.

## Decision

- **Option 3.** The booking service seals the token with AES-256-GCM before it goes into the event, and the event carries `sealedToken` instead of `token`. The notification service opens it only to build the link, and never logs it. The format is the one the system already uses for encrypted values: `v1:` and Base64 of the random 12-byte IV, the ciphertext and the 16-byte tag. The version prefix leaves room for rotating the key.
- **Bound to the event it belongs to.** The seal covers, as GCM authenticated data, the event type and the address (`account.password-link|priya@example.com`). A sealed token opens only for exactly that pair. Someone who can write to the topic therefore cannot copy a captured sealed token into a forged event for another address to have a valid link sent there: opening it fails.
- **Its own key,** `SKYHUB_LINK_TOKEN_KEY` (Base64, 256 bits), shared by the booking and notification services and by nothing else. It is separate from the personal-data, manage-token and boarding-pass keys on purpose, so none of them can stand in for another. The booking service refuses to start without it, as it does for its other keys.
- **Fail closed.** A token that does not open (the wrong key, a changed value, an address that does not match) makes that one event fail like any other: it is retried and then parked (ADR-0019, ADR-0032), and no email is sent. A notification service with no key still starts and still sends booking emails, because those do not use it; it says at start-up that account links will be parked.
- **Only these two events change.** Booking emails, the address on the topic and everything else are as before. Shared examples (`account-password-link` and `account-email-change-link`) now carry a sealed token made with a test-only key, and both services' tests read them, so the two ends cannot drift apart.
- **A clean cut, no compatibility reading.** The notification service accepts only `sealedToken`. Events already on the topic when this is deployed carry a plain `token` and will be parked (and can be skipped, ADR-0037); their links expire in an hour and the traveller asks again. Accepting both would keep the plain form alive.

## Consequences

- Positive: reading the topic no longer yields a usable token, so it can no longer be used to take over a pending account or to prove a mailbox.
- Positive: a captured sealed token cannot be moved to another address or event type.
- Positive: no new endpoint, no new credential between services beyond one key, and sending still does not depend on booking being up.
- Negative: **one more secret to manage.** It must be set the same on both services; there is no rotation procedure yet, only the version prefix that makes one possible.
- Negative: **the notification service can now decrypt tokens,** where before it merely relayed them. It could always send mail for any event it was given, so this adds little, but a compromise of it now includes the tokens in flight.
- Negative: **the address and everything else on the topic are still readable.** Only the secret is sealed; the topic is still not a place for anything beyond what ADR-0019 says it holds.
- Negative: **a deploy has a seam.** Until both services have the key, account links are parked rather than sent, and events published before the cut are parked too. Set the key on both before deploying; the parked ones are skipped or expire.
- Negative: **replay is not prevented.** The same sealed event copied to the topic with the same address would send the same email again if its id were new; the consumer records each event id once (ADR-0019), but an attacker with write access can choose a new id. The token still works once and only for an hour, and the link only goes to the address it was sealed for, so the most they can do is resend a genuine link to its genuine recipient.
- Follow-up: a rotation procedure for the key; an expiry inside the seal.
