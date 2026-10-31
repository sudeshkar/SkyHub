# ADR-0059: Reaching check-in in the live check

**Status:** Accepted
**Date:** 2026-11-08

## Context

The live check (ADR-0049, ADR-0053) proved the account and booking journeys through the real chain, but never check-in: the window opens 24 hours before departure, and the seed flights are days away. So check-out (ADR-0055), the gate check (ADR-0056), per-passenger check-in (ADR-0058) and the sign-out of a booking's sessions were each proved only by integration tests against a real database, never together with the real services. Each pull request said so.

## Options considered

1. **Move the clock** in the booking service for the live check. Needs a clock override in the service, which is test machinery in production code.
2. **Seed a flight that leaves within 24 hours.** The seed data is fixed dates; a flight "tomorrow" is only in the window for part of a day, so the check would pass or fail by the time it is run.
3. **Open the window wider for the live stack only.** The window is already a setting (`skyhub.booking.check-in.opens-before`, default 24 hours). Starting booking with 100 days changes no code and no default.

## Decision

**Option 3.** `stack.py` starts the booking service with `--skyhub.booking.check-in.opens-before=PT2400H`; `service_args` decides it and is unit-tested, including that the closing time is never touched (a flight about to leave still cannot be checked in to). Nothing in the service changes, and the default window is untouched.

`booking_journey.py` gains a check-in section, before the cancellation so the cancelled booking's pass can be checked at the end:

- one passenger checks in alone, and only their seat freezes while the other can still change seat;
- the gate accepts the pass of the passenger who is in;
- the rest check in, the first passenger keeps their number, and asking again changes nothing;
- one passenger checks out: only the other pass is left, the gate refuses the one who left (though its signature is still genuine, and the answer says nothing about them), refuses a changed pass as not signed by us, still accepts the one who stayed, and refuses a traveller's booking token and no token at all;
- everyone checks out and in again: new numbers, the old pass is refused, the new one is accepted;
- after the booking is cancelled, the gate refuses its pass.

## Consequences

- Positive: check-in, check-out, the gate check and per-passenger rules are now proved through the real chain, as a repeatable command.
- Negative: **the live stack's window is not the real one.** The 24-hour opening and the 60-minute closing relative to real time are not exercised live; they stay covered by the unit and integration tests.
- Negative: the section depends on the booking journey having found a flight that is not about to leave (it looks 3 to 9 days ahead).
- Negative: still run by hand, not in CI.
