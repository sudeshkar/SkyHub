# ADR-0049: Proving the real chain live

**Status:** Accepted
**Date:** 2026-11-03

## Context

Every service is tested against fakes, an in-process Kafka and a throwaway database. That proves each piece, and for the account journey the pieces were proven one at a time over many slices: the sealed token against a shared example (ADR-0046), the email to the old address against a mocked sender (ADR-0040), the session epoch against an in-memory request (ADR-0041). Each pull request said, honestly, "not run through the real chain". Nothing had run the whole path with the real services, Kafka and an SMTP server together. `DEVELOPMENT.md` says to prove the real chain live where it matters, but there was no repeatable way to do it, so it was done by hand, once, or not at all.

## Options considered

1. **Keep saying "not verified"** - true, and it leaves the biggest risk (the pieces do not fit together) to be found by a user.
2. **A test in the build that starts everything** - Testcontainers does not work for Kafka on the machine this is built on (`DEVELOPMENT.md`), and a test that needs two running services, a broker, a mail server and a database is a slow, fragile part of every build.
3. **Scripts that drive a stack someone has started, run by hand when the chain changes** - repeatable, cheap to keep, and kept out of the build.

## Decision

- **Option 3.** Two standard-library Python scripts in `tools/live-check/` (with their own README): `account_journey.py` and `failure_path.py`. They need Docker, the booking and notification services, and nothing else; they do not touch the website or the flight search service.
- **The journey (29 checks)** signs up with an emailed link, chooses a password, signs in, asks to change the address, confirms it from the new mailbox, and signs out everywhere, reading each email from Mailpit and the real Kafka topic. It checks the properties the ADRs promise: the topic holds only the sealed token and the plain one appears nowhere on it; the old address gets a notice with the new one masked; the open session is refused at once; a password BCrypt cannot store is a 400.
- **The failure path** gives the notification service the wrong link-token key, and checks the event is refused, retried and **parked, not sent**, with a listing that leaks nothing. Then, with the right key, it follows the runbook (fix the key, replay) and checks the link is sent and works, and that a second replay takes nothing more.
- **A check that cannot fail proves nothing:** the failure path is meant to fail when run against a correctly keyed service (nothing is parked), and does.
- **Findings go back into the code.** The first run is below.

## What the first live run found

- **All 29 journey checks passed**, and the failure path and the runbook's recovery worked as written. The counters agreed with each other: one `confirmed` change and one `account.email-changed` notice sent, and three refused tokens after the move.
- **One defect.** A development database held 44 bookings, 43 of them encrypted under an earlier key. The start-up backfill (ADR-0034) logged an ERROR line **for each unreadable booking, at every start**. After a real key mistake on a large database that is a line per booking per restart. It now logs once per run, with the count and the first five ids, and counts them in `skyhub_contact_index_unreadable_total`. Verified against the same database: 43 lines became 1.

## Consequences

- Positive: the claim "it works through the real chain" can now be made after running something, and repeated after the next change.
- Positive: the first run found a real operational defect that no unit or integration test could have, because it needed a database of old data under another key.
- Positive: the failure path and the runbook's steps are exercised, so the runbook is checked, not just written.
- Negative: **it is run by hand.** Nothing makes anyone run it, so it can be forgotten; it is not in the build, and not in CI.
- Negative: **the failure path needs a restart in the middle,** which a script cannot do, so it is two runs with a manual step between them.
- Negative: it covers one traveller's account journey. Bookings, payments, check-in and the operations screen (which need the flight search service) are not covered, nor is the website beyond what its own tests and `npm run e2e` do.
- Negative: it checks correctness, not load or timing.
- Follow-up: a booking-and-payment journey through the real chain; a script that starts and stops the stack itself so the whole thing is one command.
