# ADR-0075: Three loose ends from the check-in work

**Status:** Accepted
**Date:** 2026-11-03

## Context

Slices 59 to 70 (boarding, undoing a boarding, check-in confirmation, the QR attachment) each named something small they had left behind. None changes what a traveller or a staff member can do; each is a place where the code or a sentence said something that had stopped being true or had a second copy. They are done together because each is a few lines, and kept as three separate commits so each can be read and reverted alone.

## Decisions

### 1. The check-in-open email no longer says everyone checks in together

Since passengers can check in one at a time (ADR-0058) the email's "Everyone on the booking checks in together" was wrong. It now says the traveller can check in everyone at once or one passenger at a time, and that the last name of **any passenger** opens the booking (it said "your last name", which is not what Manage booking asks). A test fails if the old sentence returns.

### 2. One place answers "when will this flight really leave?"

`ExpectedDepartures` (ADR-0063) was written for boarding and `CheckInService` had its own copy of the same rule (a recorded delay beats the timetable; a cancelled flight has no departure), twice: once for checking in and once for checking out. Both now use it. The contract changed so that it fits all three callers:

- `ExpectedDepartures.of(flight, date)` returns an **`Optional<Instant>`**, **empty for a cancelled flight** (and the timetable is not asked), the revised time for a delayed one, the timetable's otherwise. It still throws when the flight does not operate or the timetable is unavailable.
- **What empty means is the caller's:** check-in refuses a cancelled flight (and counts it); check-out of a cancelled flight is always allowed (ADR-0055); boarding refuses it as `FLIGHT_CANCELLED`. The last case is only reachable if a flight is cancelled in the moment between checking a pass and checking the window, and a test pins it.
- `CheckInService` no longer holds the disruption repository or the flight catalogue, which is one dependency fewer for the next feature to ask about. The existing check-in, delay, cancel and check-out tests passed unchanged apart from how the service is built, which is what shows nothing else moved.
- Cost: no extra query. One disruption lookup, and one timetable call when there is none, as before.

### 3. The sealer and opener are named for what they do

`LinkTokenSealer` and `LinkTokenOpener` (and their AES-GCM adapters and the test helper) now seal boarding passes too (ADR-0074), so they are renamed `SecretSealer`, `SecretOpener`, `AesGcmSecretSealer`, `AesGcmSecretOpener` and `TestSealedSecrets`. Their parameters are `secret` and `sealed`, and the messages say "sealed value". **Nothing on the wire changes:** the `v1:` format, the bound event type and address, the key, and the setting `skyhub.security.link-token-key` / `SKYHUB_LINK_TOKEN_KEY` are exactly as they were. The setting keeps its name because it is a deployed contract: renaming it would break every running environment for no gain. Older ADRs still use the old class names; they are a record of what was true then.

## Consequences

- Positive: a traveller is told the truth about checking in; the rule for a delayed or cancelled flight has one copy; the names say what the code does.
- Positive: no behaviour a person can see changed except the email's sentence.
- Negative: **the setting is still called `link-token-key`** though it seals more than links. A future rename would need a deprecation period reading both names.
- Negative: **`ExpectedDepartures.of` now returns an `Optional`**, which a caller can forget to handle. All three callers do, and a test covers each reading of "empty".
- Negative: the older ADRs and some comments still say "link token" where they mean the first use; they are left as history.

## Verification

A test for the email's sentence (written first and seen to fail), tests for the new `ExpectedDepartures` contract (a cancelled flight gives empty without asking the timetable; delayed and on-time give their times) and for the boarding case of a flight cancelled between the check and the window, and the whole existing check-in and check-out suite passing against the refactored service. The rename was checked by the compiler and by the whole existing suite of both services. Full clean builds: booking with 1003 tests and notification with 180, 0 failures. **Totals quoted earlier in this series came from builds without `clean`, which can sum report files from renamed or removed test classes, so they may be a few higher than the true counts.** Nothing was sent through a real mail server and the live check was not run.
