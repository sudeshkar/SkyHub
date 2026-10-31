# ADR-0011: Manage a booking with a short-lived, booking-scoped token

**Status:** Accepted
**Date:** 2026-10-29

## Context

Guests book without an account. To view, change or cancel a booking later, they identify themselves with the booking reference and a passenger's last name. The first version sent that pair in the URL of every request (`GET /pnrs/{locator}?lastName=…`). That has three problems:

- the last name travels in a URL, which ends up in logs, caches and browser history;
- every later operation (cancel, change seat) would have to repeat the check, or skip it;
- there is no way to limit what a proven caller may touch.

## Options considered

1. **Last name on every request** — simple, but personal data in every URL and nothing stops a caller who learned one reference from guessing more.
2. **Server-side session with a session ID cookie** — works, but needs shared session storage across instances and ties the Java service to cookies.
3. **Signed, short-lived token (JWT) issued after one ownership check** — stateless, verified by Spring Security's resource server, scoped to one booking.

## Decision

Option 3.

- `POST /api/v1/pnrs/{locator}/sessions` checks the last name once and returns an HS256 JWT valid for **15 minutes**. Unknown reference and wrong last name return the **same 404**.
- The token's subject is the locator and it carries the scope `manage`; it holds no name and no email. Every manage endpoint requires a token **whose subject equals the locator in the URL** (`OwnsBookingAuthorization`): no token is 401, a token for another booking is 403.
- The token is signed with its own key, `SKYHUB_MANAGE_TOKEN_KEY` (256-bit, required at startup), kept separate from the key that encrypts personal data (ADR-0010) so neither can stand in for the other.
- The Next.js server keeps the token in an **httpOnly, SameSite=Strict cookie** scoped to `/manage`, which only the server reads. Scripts on the page cannot see it, and it is never in a URL.
- The application layer depends on a port, `ManageTokenIssuer`; the JWT implementation lives in `security`. `ManageToken.toString()` never prints the token.
- Changing a seat and cancelling are each one transaction over the booking and its seat rows, and `Pnr` is optimistically locked, so a cancel and a seat change cannot both succeed.

## Consequences

- Positive: the last name is sent once per session, not on every request; a stolen token opens one booking for at most 15 minutes.
- Positive: stateless — any instance can verify any token.
- Negative: a token cannot be revoked before it expires (for example after "close this booking"); the cookie is dropped but a copied token stays valid until expiry. A revocation list would fix this if needed. (Done differently, with a session epoch: ADR-0057.)
- Negative: rotating the signing key signs everyone out. Acceptable with a 15-minute lifetime.
- Follow-up: alert on `skyhub_manage_actions_total{action="session_denied"}` (done); an optional second factor, such as a code sent to the contact email, for high-value changes.
