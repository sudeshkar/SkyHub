# ADR-0045: One login lock and one password gate for staff and traveller accounts

**Status:** Accepted
**Date:** 2026-11-02

## Context

Staff accounts (ADR-0017) and traveller accounts (ADR-0033) check a password the same way: BCrypt, the same cost for an unknown name as a real one (a decoy hash), a locked account refused even with the right password, and five wrong passwords locking it for fifteen minutes. That behaviour is written down twice, word for word:

- the lock state (`failedAttempts`, `lockedUntil` and the three methods that move it) is copied into `StaffAccount` and `TravellerAccount`;
- the decision (`StaffService.attempt` and `AccountService.verify`) is copied into both services, each with its own decoy hash and its own outcome type.

ADR-0033 and ADR-0039 both list the copy as a negative. It is also a security risk of the quiet kind: a lock rule fixed or tightened in one place and not the other leaves one kind of account weaker, and nothing would show it. Changing the address (ADR-0039) already had to reuse the traveller copy, so a third caller was one more paste away.

## Options considered

1. **Leave the copies** - nothing to review or break, and the next change to the lock is a change in two places that must agree.
2. **A shared base class for the two accounts** - removes the fields and methods, but the two accounts share nothing else, and inheritance would tie a staff member to a traveller's shape for one rule.
3. **Composition: one small `LoginLock` the accounts hold, and one `PasswordGate` the services use** - each rule lives once; the accounts keep their own shape and their own tables.

## Decision

- **Option 3, and no behaviour changes.** This is a refactor: every existing test must pass unchanged in what it asserts, and the columns and tables are the same.
- **`LoginLock`** is an embeddable part of an account (`failed_attempts`, `locked_until`, the same columns as before) with the limits as constants (five tries, fifteen minutes) and three moves: record a failure (starting afresh if an old lock has run out, locking on the fifth), record a success (forgetting the count and any lock), and ask whether it is locked at an instant. `StaffAccount` and `TravellerAccount` each hold one and keep their existing methods, which now delegate, so no caller changes.
- **`PasswordProtected`** is the small interface both accounts implement for what the gate needs: the password hash (absent for a traveller who has not chosen one), whether it is locked, and recording a failure or a success.
- **`PasswordGate`** is the decision, once: given an account (or none) and a password it answers `SUCCESS`, `FAILED` or `LOCKED`, and records the result on the account (the caller saves it). An unknown account, or one with no password, is checked against the decoy hash so it costs the same; the password is checked even when the account is locked, so a locked account costs the same too; a locked account is refused even with the right password. It holds the one decoy hash, so it is computed once at start-up instead of once per service.
- **Both services use it:** `StaffService.login`, `AccountService.login` and `AccountService.requestEmailChange` (ADR-0039) call the gate and no longer decide anything themselves. What they do with each answer (metrics, logs, which exception) stays in the service, because that differs: staff answer 401 "Login failed", travellers 401 "Sign-in failed", and a wrong password when changing the address is a 403.
- **Open for extension:** a third kind of account that has a password and a lock implements `PasswordProtected` and uses the gate; changing the limits is changing one constant.

## Consequences

- Positive: the lock rule and the password decision each exist once, so staff and travellers cannot drift apart, and the next change is made and tested in one place.
- Positive: less to read in the two services, and a faster start (one BCrypt decoy instead of two).
- Positive: the gate is a plain class with no database, so its rules are tested directly, including the timing-relevant paths (unknown, no password, locked all call the hasher once).
- Negative: **the limits are now one constant for both,** so staff and travellers can no longer be given different limits without a change to the lock. That is the same as today (both are five and fifteen minutes); making them configurable per kind of account would be a new decision.
- Negative: **one more small type to know about** (`PasswordProtected`) where there were two classes that happened to look alike. The cost is a line to implement it on a new kind of account.
- Negative: the embeddable adds a level to the entities, which is invisible in the tables but is a change to how Hibernate maps two entities; the existing integration tests that lock an account and find it still locked on the next request are what prove the columns still persist.
- Follow-up: the same treatment for the password-length rules and BCrypt cost that both services repeat (the length rules: done, ADR-0048, which also found that the old limits accepted passwords BCrypt cannot store).
