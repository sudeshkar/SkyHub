# ADR-0040: Telling the old address when an account moves

**Status:** Accepted. The decision that the notice holds no link was changed by ADR-0054, which adds a narrow undo link.
**Date:** 2026-11-01

## Context

Changing the account's address (ADR-0039) needs the password, a signed-in session and a mailbox at the new address. That is hard to do without being the owner, but it is not impossible: a stolen session plus a guessed or reused password, and a mailbox the attacker controls, is enough. If it happens, the owner learns nothing until they next fail to sign in. The one place that could warn them is the address the account has just left. ADR-0039 listed this as its main negative.

## Options considered

1. **Nothing** - what exists. The owner is locked out and unaware.
2. **A notice with a link to undo the change** - the useful kind of warning, but "undo" means moving the account back to the old address using something only that mailbox holds, which is a second way to change an account's address. It would need its own token, expiry, and checks, and it would let anyone who reads an old mailbox (a former address that now belongs to someone else) take an account back. That is a bigger design than a notice.
3. **A notice with no link and no secret** - tells the owner what happened and when, and to act through support. It adds no way to change an account, so it cannot be abused.

## Decision

- **Option 3.** When a change is confirmed (ADR-0039), an event `account.email-changed` is written to the outbox **in the same transaction as the change**, so a notice exists exactly when a change does, and a refused or failed confirmation sends none. The notification service emails it to the **old** address.
- **The notice holds no secret and no link.** The event carries the old address (to send to), the time, and the new address **masked** (`p****@example.com`), enough for the owner to recognise it. The full new address is never in the event, so a stale mailbox is not told who now holds the account. There is no token, so nothing sensitive passes through Kafka.
- **The email says what happened and what to do:** the account's address changed and the masked new one; that nothing needs doing if they did it; and that if they did not, someone with their password and access to the new mailbox did, so they should tell SkyHub support at once. It does not offer a way to undo it.
- **It is sent once per confirmed change.** Confirming uses a token that works once, and the event is written in the confirming transaction, so the consumer's record of each event id (ADR-0019) is the only dedupe needed.
- **Nothing else changes:** no new endpoint, no website change, no change to what confirming returns.
- **Monitoring:** the existing counters cover it: `skyhub_account_email_changes_total{outcome="confirmed"}` counts changes and `skyhub_notifications_total{type="account.email-changed"}` counts the notices, so a gap between them is visible. No alert of its own.

## Consequences

- Positive: an owner whose account was moved without them gets a message at the address they still read, with no extra way to attack the account.
- Positive: no secret in the event, so nothing to expire, hash or guard on the topic.
- Positive: exactly one notice per change, atomically with it.
- Negative: **it only informs.** The owner cannot undo the change themselves; they depend on support, and the runbook says what a developer does (a database change). Self-service recovery is a design of its own (option 2) and is not built.
- Negative: **an address that has since been given to someone else** gets a notice about an account that is not theirs. It says only that an account at that address moved and a masked new address, so it leaks little, but it is noise to them.
- Negative: **a notice that is not delivered** (the mailbox is gone, or mail is down past the retries) is parked like any other (ADR-0032); the change has already happened.
- Negative: sessions already issued stay valid for up to 30 minutes after a change, so an attacker already inside is not stopped by this. (Ended by the change since ADR-0041.)
- Follow-up: revoke sessions on an address change; a way for the owner to recover the account; a shared lock class for staff and traveller accounts.
