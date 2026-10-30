# ADR-0017: Staff login and roles instead of a shared operations key

**Status:** Accepted
**Date:** 2026-10-30

## Context

Recording a flight disruption (ADR-0014) is guarded by one shared secret, `SKYHUB_OPS_KEY`. It was a deliberate stop-gap, and its costs are now the weakest point of the system:

- **No identity.** Anyone holding the key is "operations". When a flight is wrongly cancelled, nobody can say who did it.
- **No separation of duties.** The key can do everything or nothing. Staff who may disrupt flights and staff who may create other staff are the same people.
- **No clean way to take access away.** Removing one person means rotating the key for everybody.
- Coming work needs a real identity: the operations screen, and an agent that proposes disruptions and must be accountable for them.

## Options considered

1. **Keep the shared key, add an audit header** — the caller says who they are. Anyone can lie; this is a label, not an identity.
2. **External identity provider (Keycloak, Entra ID, OIDC)** — the right answer for a real airline, and the follow-up. It is a second system to run and secure before this one has a single staff screen.
3. **Staff accounts in the booking service, with the same stateless token mechanism that already guards bookings (ADR-0011)** — one table, one login call, a signed short-lived token that carries the role.

## Decision

Option 3. Staff accounts live behind a port, so swapping in an external provider later changes the adapter and not the rules.

- **Accounts.** Table `staff_account`: unique lower-case username, a **BCrypt** password hash (never the password), a role, a failed-attempt counter and a lock time. Passwords are at least 12 characters.
- **Roles.** `OPS` may record disruptions. `ADMIN` may do that **and** create staff accounts (`POST /api/v1/ops/staff`). Everything else is denied by default. A role is checked on every request from the token, not from the database, so a request costs no lookup.
- **Login.** `POST /api/v1/ops/sessions` with a username and password returns an HS256 token valid for **30 minutes**, scope `staff`, with the role as a claim. It is signed by the same key as manage tokens and told apart by scope: the booking rule requires `manage` **and** a subject equal to the locator, the staff rule requires `staff` **and** a role, so neither token opens the other's doors.
- **Same answer for every failure.** Unknown user, wrong password and locked account all return the same 401, and an unknown user still pays for a password check, so neither the response nor its timing says which usernames exist.
- **Lock-out.** Five wrong passwords lock the account for 15 minutes; a correct login clears the count. The lock is real even for the right password, so guessing gains nothing during it. The downside is that someone can lock a known username on purpose. Mitigation: it lasts 15 minutes, an alert fires on it, and rate limiting at the edge (ADR-0009) slows the attacker first.
- **First administrator.** If no account exists at startup and `SKYHUB_BOOTSTRAP_ADMIN_USERNAME` / `SKYHUB_BOOTSTRAP_ADMIN_PASSWORD` are set, that account is created once as `ADMIN`. After that the variables are ignored. Without them the service starts with no staff and the operations endpoints are unreachable, as the old key's "no key, closed" rule did.
- **Audit.** Each recorded disruption stores the username that recorded it (`recorded_by`), and the log line carries it. Account creation is logged with who created whom.
- **The old key is removed**, not kept as a fallback. Two ways in is the larger of the two risks.

## Consequences

- Positive: every disruption has a name against it; access is removed by disabling one account, not rotating a secret for everyone.
- Positive: no new infrastructure, no new token mechanism, and no database read to authorise a request.
- Negative: a staff token cannot be revoked before it expires (the same limit as ADR-0011), so a removed person keeps access for up to 30 minutes. Acceptable for now; a revocation list is the fix.
- Negative: we now store password hashes, which is a new thing to protect and to get wrong. That is the cost of not using an identity provider.
- Negative: no password reset, no second factor, and no screen to disable an account. All are follow-ups.
- Follow-up: replace the adapter with OIDC single sign-on and multi-factor; the ops screen; a service account for the disruption agent.
