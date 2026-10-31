# ADR-0041: Ending sessions when an account's credentials change

**Status:** Accepted
**Date:** 2026-11-01

## Context

An account token is a signed, self-contained credential that lasts 30 minutes (ADR-0033). That is what makes it cheap, and it also means nothing can take one back. Two things change who controls an account: moving it to another address (ADR-0039) and choosing a new password with an emailed link (ADR-0033). After either, every session that was already open still works for up to half an hour. In the case these features exist to defend against, a stolen session, that is exactly the session the owner most wants gone: the owner resets the password or sees the notice of a move (ADR-0040), and the attacker's token keeps working. ADR-0039 and ADR-0040 both list this as their main remaining limit.

## Options considered

1. **A deny-list of token ids** - store the id of every revoked token. Grows with every revocation, has to be kept until each token would have expired, and still cannot revoke tokens whose ids were never recorded (all of them, at the moment of a reset).
2. **"Valid from" timestamp per account** - reject tokens issued before it. Tokens carry their issue time in whole seconds, so a token issued in the same second as the change is ambiguous: either a fresh sign-in is refused or an old session survives for a second, and the right answer depends on clocks agreeing.
3. **A session counter per account, carried in the token** - the account has a number that goes up when its credentials change; a token records the number it was issued under and is valid only while it still matches. No clocks, nothing to expire, one comparison.

## Decision

- **Option 3.** `traveller_account.session_epoch` starts at 0. The account token carries it as the claim `epoch`; a token without the claim counts as 0, so tokens issued before this change stay valid until the first revocation and nobody is signed out by the upgrade.
- **Credentials changing ends all sessions:** confirming a new address (ADR-0039) and choosing a password with an emailed link (ADR-0033) each add one to the epoch, in the same transaction as the change. A password link also ends sessions when it is used to sign up, which has none, so this costs nothing there.
- **Checked on every account request** by a token validator in the security layer: it asks a port (`AccountSessions.accepts(accountId, epoch)`) whether the token's epoch is the account's current one. One lookup by primary key, no cache, so a revoked token is refused on its very next request. A token for an account that no longer exists is refused too. A refused token is an ordinary 401, so the website already treats it as an ended session.
- **Only account tokens are checked.** Manage tokens (one booking, 15 minutes) and staff tokens are separate credentials with their own rules, and the validator never consults the account for them.
- **The person who made the change is signed out too,** since their session belongs to the old epoch. After moving the account they sign in again with the new address, and the confirmation page says so and drops its cookie.
- **No new endpoint and no new table:** one column, one claim, one port.
- **Monitoring:** `skyhub_account_sessions_total{outcome}` (accepted is not counted; `revoked` is) so a run of revoked tokens after a change is visible, and the runbook entry says what it means.

## Consequences

- Positive: after a password reset or an address change, an attacker's open session stops working at once, not within half an hour.
- Positive: no clocks, no deny-list, nothing to clean up; a token that is not revoked costs one comparison.
- Positive: it closes the limit listed in ADR-0039 and ADR-0040 with no change to the account endpoints themselves.
- Negative: **one database read per account request.** It is a primary-key lookup on a small table, on endpoints that already read the account, and it is what makes revocation immediate. A short cache would cost that immediacy.
- Negative: **it is all or nothing.** There is no list of sessions and no way to end one of them; ending all of them is what changing credentials does. A "sign out everywhere" button would be one more place that adds one to the epoch.
- Negative: **booking tokens already issued from an account stay valid for up to 15 minutes.** Opening a booking from the account (ADR-0034) gives an ordinary manage token for that one booking, which is independent of the account's session by design.
- Negative: **a password link used by the owner ends their own other sessions,** including on a device they were happily using. That is the price of treating a reset as a possible compromise.
- Negative: tokens issued before this change carry no claim and so count as epoch 0; if an account's epoch is later raised, they are revoked like the rest, which is intended.
- Follow-up: a "sign out everywhere" action (since ADR-0047); a way for the owner to recover an account that was moved without them.
