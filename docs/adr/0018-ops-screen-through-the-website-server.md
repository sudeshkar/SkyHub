# ADR-0018: The operations screen is a page on the website, and its token stays on the server

**Status:** Accepted
**Date:** 2026-10-30

## Context

Operations staff can now log in (ADR-0017), but recording a delay or cancellation still means sending a raw API request. They need a small screen for it. It must not weaken what ADR-0017 built: a staff token is more powerful than a booking token, and the public website is the part of the system most exposed to the internet.

## Options considered

1. **A separate staff web application** — the cleanest separation, and the right answer once there are several staff screens. Today it would be a second Next.js project, build and deployment for one form.
2. **A page in the existing website that calls the booking service from the browser** — simple, but the staff token would live in browser JavaScript, where any script injected into the page could read it.
3. **A page in the existing website whose server does the calling, with the token in an httpOnly cookie** — the same pattern as managing a booking (ADR-0011). The browser holds a cookie it cannot read; only the Next.js server ever sees the token.

## Decision

Option 3, at `/ops`.

- **Login and the form are server actions.** The password goes from the form to the Next.js server to the booking service. It is never in a URL, and the browser never calls the Java service.
- **The token is in an httpOnly, SameSite=Strict cookie scoped to `/ops`,** living at most as long as the token (30 minutes). It is not sent to any other page of the site. Malformed cookies count as "not signed in".
- **The page decides nothing about permissions.** The booking service checks the role on every request. If the token is expired or the role may not do the action, the answer is 401 or 403 and the page sends the person back to sign in. Hiding a button would not be a security control, so none is relied on.
- **Login is rate limited** to 10 attempts per 15 minutes per client, in front of the service's own lock-out after five wrong passwords per account.
- **One plain message for every login failure**, as in the API.
- **Not linked from the public site and marked `noindex`.** That is tidiness, not protection: the page is safe to find because it shows nothing without a login.
- **Times are entered and stored in UTC** and labelled so (ADR-0005), so a delay entered in one time zone cannot be read in another.
- The page shows how many active bookings the flight has, which the service already returns, so staff see the size of what they just recorded.

## Consequences

- Positive: no new application, no new deployment, and the staff token is never reachable from page scripts.
- Positive: the permission rules live in one place, the booking service, so adding a role cannot be forgotten in the page.
- Negative: the staff screen shares a deployment with the public site, so a vulnerability in the public pages is closer to it than a separate application would be. The mitigations are the cookie scope, the short token life, and the service-side checks. Option 1 is the follow-up if staff screens grow.
- Negative: no list of past disruptions yet, so staff can record but not browse. Recording again corrects a mistake, and the booking service stores who did each one.
- Negative: an administrator cannot create or disable staff from the page. That is still an API call.
- Follow-up: a list of recent disruptions; staff administration screen; single sign-on in place of passwords (ADR-0017).
