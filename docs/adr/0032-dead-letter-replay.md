# ADR-0032: Putting parked notifications back

**Status:** Accepted
**Date:** 2026-10-31

## Context

A notification that fails after its retries is copied to the dead-letter topic `skyhub.booking.events.DLT` (ADR-0019). An alert tells a person, who fixes the cause (usually the mail server). Then the runbook said to publish each parked message back to the booking events topic by hand. That is slow, easy to get wrong, and the only place in the email path where a traveller's email could be lost for good by a slip.

## Options considered

1. **Automatic replay** - retry parked messages on a timer. A message that fails for a lasting reason would loop for ever, and replaying before the cause is fixed only fills the topic again.
2. **A script run on a laptop** - needs broker access and credentials on someone's machine.
3. **An operator endpoint on the service** - runs where the credentials already are, behind the management port.

## Decision

- **A replay is a person's decision** (option 3, no timer). Two operations on an actuator endpoint, `deadletters`, on the management port, which is never exposed publicly (ADR-0010): `GET` shows what is parked, `POST` with `{"limit": n}` puts that many back. So the tool needs no login and no public API.
- **Progress is a consumer group's committed offsets.** A replay takes the messages after the last one replayed. Nothing is deleted from the topic. Offsets are committed only after the broker has confirmed each republished message, so a failure part-way leaves the rest for the next replay.
- **Replaying twice is harmless:** the notification consumer records each event id once, so a message that was in fact handled sends nothing.
- **Bounded:** at most 1,000 messages a call (100 by default), one replay at a time per instance; a second call at once answers `busy` and does nothing.
- **The listing never shows the message body** (it holds the traveller's address) and hides any address that an error message happens to contain. It shows the kind of event, partition, offset, time and the reason.
- **A counter**, `skyhub_notifications_replayed_total`, records what was put back.
- A message that fails again is parked again at a new offset, and the next replay takes it. That is deliberate: a loop needs a person each time.

## Consequences

- Positive: recovering from a mail outage is one command, repeatable, and cannot lose a message.
- Positive: no new infrastructure, and the listing is safe to paste into a ticket.
- Negative: **two instances replaying at the same moment can republish the same message twice.** The lock is per instance. This is harmless because of the idempotent consumer, and the cost is a duplicate record skipped.
- Negative: messages are replayed in offset order within a partition, not in the order the events happened across partitions. Emails are independent, so this does not matter.
- Negative: there is no way to skip or delete one parked message that can never succeed; it will be taken (and parked again) by every replay. Skipping by offset is the follow-up.
- Negative: replay takes messages from the committed point onward only; going back to re-send something already replayed means resetting the consumer group's offsets by hand.
