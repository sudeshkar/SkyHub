# ADR-0047: Signing out everywhere

**Status:** Accepted
**Date:** 2026-11-03

## Context

Changing the password or the address ends every open session on an account (ADR-0041). That covers the cases where the credentials change, but not the commonest worry: "I signed in on a computer that is not mine and left", or "my phone was lost". Today the only way to end those sessions is to change the password, which is a heavy thing to do for a lost phone, and "Sign out" on the account page ends only the browser it is pressed in (the cookie is dropped; the token itself stays valid until it expires). ADR-0041 named a "sign out everywhere" action as its follow-up.

## Options considered

1. **Tell people to reset their password** - works, but changes a credential that did not need changing and makes the owner choose a new one.
2. **A list of sessions, each with its own "end this one"** - the friendliest, and it needs a record of every session (a table, a way to name a session, expiry cleanup), none of which exists: tokens are stateless by design.
3. **One action that adds one to the account's session epoch** - the mechanism already ends every session when the credentials change; this is the same move on request, with no new storage.

## Decision

- **Option 3.** `DELETE /api/v1/accounts/me/sessions` (an account token) ends every session of the signed-in account, including the one that asked, and answers 204. It adds one to the session epoch (ADR-0041) in a transaction, so every token issued before is refused on its next request, and a fresh sign-in works at once.
- **No password is asked for.** Ending sessions can only make the account harder to use, never easier to take, so it is not a thing to protect with a password prompt: someone with a stolen session could end sessions, but could not do anything they could not already do, and the owner can sign in again and does lose nothing but a sign-in.
- **It leaves everything else alone:** the password, the address, bookings, and the booking tokens already opened from the account (ADR-0034), which are separate credentials for one booking and expire on their own within 15 minutes.
- **The website** puts a "Sign out everywhere" button on the account page, with a line saying what it does. Pressing it ends every session and drops this browser's cookie, then shows the sign-in page. If the booking service cannot be reached the cookie is kept and the page says so, since a person who thinks they are signed out everywhere should not be told so when they are not. A token the service no longer accepts has nothing to end, so that too leaves the person signed out.
- **Monitoring:** the existing `skyhub_account_sessions_total` gains an outcome `signed_out_everywhere`, so a person doing it is visible beside tokens refused after it (`revoked`).

## Consequences

- Positive: a lost phone or a forgotten shared computer is dealt with in one click, without changing a credential.
- Positive: no new table, token or concept; one method on the account that the other two changes already used.
- Positive: ending sessions cannot be used to take an account, so it needs no extra proof.
- Negative: **it is all or nothing,** as ADR-0041 says. There is no list of devices and no way to end one, and the person who presses it signs in again too.
- Negative: **it does not stop someone who already has the password,** who can simply sign in again. For that, change the password (which also ends every session).
- Negative: **a nuisance is possible:** anyone holding a valid session can end the owner's other sessions. They could already do far more with that session, so it adds no new power, but it is a way to log the owner out.
- Negative: opened booking tokens (up to 15 minutes) survive it, by design.
- Follow-up: a list of sessions with a name and last-used time, if per-device control is ever wanted; it would need stored sessions.
