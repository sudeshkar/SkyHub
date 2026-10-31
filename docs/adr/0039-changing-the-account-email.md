# ADR-0039: Changing the account's email address

**Status:** Accepted
**Date:** 2026-11-01

## Context

An account's address is its identity: it is what signs a traveller in, what a password reset is sent to, and (ADR-0034) what decides which bookings they see. A traveller who changes address has no way to move their account, so they would have to start again and lose sight of their bookings. But changing the address is the most dangerous thing an account can do. Whoever can change it owns the account, and the address they move it to decides whose bookings they see afterwards. ADR-0033 was built so nobody could claim an address they cannot read; this must not undo that.

## Options considered

1. **Change it on request** - one form, one write. Anyone holding a signed-in session (a shared computer, a stolen token) could redirect the account, and could claim any address, including someone else's, to read their bookings.
2. **Ask for the password, then change it** - stops a stolen session, but still lets the person claim an address they cannot read, so they would see that address's bookings.
3. **Ask for the password, prove the new address with an emailed link, then change it** - the same proof that signing up needs, plus the password, so neither a borrowed session nor a made-up address works.

## Decision

- **Option 3**, in two steps, both for an `account` token only:
  - `POST /api/v1/accounts/me/email-change {newEmail, password}` checks the current password, stores the new address as **pending** (encrypted, with its keyed hash), and emails a link to the **new** address. It answers 202.
  - `POST /api/v1/accounts/me/email-change/confirmation {token}` spends the link. It needs the signed-in account **and** the token, and then changes the address.
- **The password is checked the way sign-in checks it** (BCrypt, the same lock after five wrong tries, a locked account refused even with the right password). A wrong or locked answer is a 403 "Password not accepted", never a 401, so the website does not read it as an ended session. Asking to change the address cannot be used to guess the password without the same lock.
- **The new address is proven, not trusted.** Nothing changes until someone who can read the new mailbox uses the link, while signed in to the account. The link is 256 bits, stored only as a SHA-256 hash, works once, and for one hour; asking again replaces it, and a request within two minutes of the last sends nothing. A wrong, used or expired link all give the same 400 as an account link (ADR-0033).
- **Nothing reveals which addresses have accounts.** If the new address already belongs to another account, the answer is the same 202 and nothing is sent. The one thing that is said is "that is already your address", which tells the caller nothing they did not know.
- **Confirming also kills any password link that was already out.** A reset link emailed to the old address would otherwise stay valid for up to an hour, and whoever still reads the old mailbox could use it to set a password on an account that has moved on. Changing the address clears it.
- **If the new address is taken in the meantime,** by an account that confirmed first, the unique index refuses the change and the answer is the same 400 as a bad link.
- **The email goes through the outbox, Kafka and the notification service** (ADR-0019) as a new event, `account.email-change-link`, with a shared example read by both services' tests. The link points at `SKYHUB_SITE_URL`, to `/account/confirm-email`, a page kept out of search results that sends no referrer.
- **Bookings follow the address,** because "Your bookings" is by address (ADR-0034): after a change, bookings made with the old address are no longer listed, and bookings made with the new one are. Nothing about a booking changes.
- **Monitoring:** `skyhub_account_email_changes_total{outcome}` (requested, throttled, taken, password_refused, confirmed, invalid_link) and a runbook entry. No alert of its own: a run of `password_refused` is already bounded by the lock, and the failed-sign-in alert does not cover it, which is noted below.

## Consequences

- Positive: moving an account needs the password, the session, and a mailbox at the new address, so no one of those is enough.
- Positive: an address cannot be claimed without being read, so the bookings of someone else's address stay theirs.
- Positive: no new credential, no new table: five columns on the account.
- Negative: **the old address is not told.** A person who loses control of an account is not warned at the old address that it moved. That needs a second event and email, and is the next step; the password and the new mailbox are what stand in for it now. (Added later: ADR-0040.)
- Negative: **the one-time token passes through Kafka,** as the account link's does (ADR-0033), and sits in the topic for its retention period.
- Negative: **a failed password here counts toward the same lock as sign-in** but is not in the failed-sign-in alert (`skyhub_account_logins_total`), so a burst of wrong passwords against this endpoint is only visible in its own counter.
- Negative: bookings made with the old address disappear from the list, and there is no way to bring them along. They can still be opened with their reference and last name.
- Negative: one pending change at a time; asking again replaces it.
- Negative: sessions already issued stay valid after the change (they name the account, not the address), up to 30 minutes, as with every token.
- Follow-up: tell the old address; revoke sessions on a change; fold the lock logic shared with staff accounts into one class.
