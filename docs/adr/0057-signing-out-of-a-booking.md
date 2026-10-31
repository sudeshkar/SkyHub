# ADR-0057: Signing out of a booking ends every session of it

**Status:** Accepted
**Date:** 2026-11-06

## Context

A booking is managed with a 15-minute token that proves the last name (ADR-0011). Closing the session only dropped the token from the browser: a copy of it, from a shared computer, a screenshot or a log, stayed valid until it expired. ADR-0011 named this: "a token cannot be revoked before it expires". Accounts already end every session on request (ADR-0041, ADR-0047) with a session epoch in the token; bookings had nothing.

## Options considered

1. **A deny-list of token ids.** Every sign-out adds ids that every request must check, the list has to be pruned when tokens expire, and a token that was never seen by the server cannot be listed.
2. **Put a version on the booking row** and compare. It would share the booking's optimistic-lock version, so signing out could make a concurrent seat change or cancellation fail.
3. **The same epoch as accounts, kept in its own small table.** A token carries the epoch it was issued under and is valid only while that is the booking's current one. Signing out raises it. Nothing about the booking's own row changes.

## Decision

**Option 3.**

- **`DELETE /api/v1/pnrs/{locator}/sessions`**, with the booking's own token. It raises the booking's epoch by one: the token used, **every other token issued before** (a copy included), and nothing for other bookings. It needs no password, because it can only make the booking harder to reach.
- **A booking token carries `epoch`** (the booking's current one when it was issued). `RevokedBookingSessionValidator` refuses a `manage` token whose epoch is no longer current, as an ordinary invalid token: **a 401**, which the website already treats as an ended session. Account and staff tokens are not looked at by it.
- **The epoch lives in `booking_session_epoch`**, one row per booking anyone has signed out of, read by primary key on every manage request and never cached, so a revoked token is refused on its very next request. No row means epoch 0. Raising it is one `INSERT ... ON CONFLICT DO UPDATE`, so two sign-outs at once both count.
- **Tokens issued before this change have no claim and count as epoch 0**, so nobody is signed out by the upgrade; they end at the booking's first sign-out.
- **An account that opens a booking** (ADR-0034) gets a token for the current epoch too: both ways in go through `ManageBookingService.tokenFor`. `MyBookingsService` no longer holds a token issuer.
- **The website's "close session" now calls it** before dropping the cookie. If the call fails, this browser is still signed out and the token lapses by itself.
- Counts in `skyhub_manage_actions_total`: `sessions_ended` (a sign-out) and `session_revoked` (a token refused because of one).
- Cancelling a booking does **not** end its sessions: the traveller still needs to see the refund.

## Consequences

- Positive: signing out of a booking now means what it says, even on a shared computer, and a copied token stops working at once.
- Positive: no deny-list, nothing to prune; one indexed lookup by locator per manage request.
- Negative: **every request to manage a booking now costs one extra read.** It is by primary key and never cached; it has not been measured under load.
- Negative: **signing out ends sessions opened by someone else with the last name, too** (a family member). They prove the last name again.
- Negative: **it does not stop someone who knows the last name** from opening a new session; the last name is the proof (ADR-0011). It limits a stolen token, not a known secret.
- Negative: an epoch only goes up. A booking signed out of many times keeps one row with a larger number.
