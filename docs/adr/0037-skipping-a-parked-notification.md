# ADR-0037: Skipping a parked notification that can never succeed

**Status:** Accepted
**Date:** 2026-11-01

## Context

A parked notification can be put back (ADR-0032). Some can never succeed: a message that is malformed, or one for an event type that no longer exists. Every replay takes it, fails it, and parks it again at a new offset. The runbook said to replay only when the cause was fixed, but here the cause cannot be fixed, so such a message makes every later replay noisy and keeps the "messages are parked" signal on for good. The one tool that exists is all-or-nothing: replay takes everything after the committed offset.

## Options considered

1. **Delete the message from the dead-letter topic** - Kafka cannot delete one record from the middle of a topic. Truncating by offset would also drop everything before it.
2. **Move the committed offset past it by hand** - the offset is one pointer per partition, so this skips every message before the one chosen, good or bad.
3. **Keep a small list of offsets to leave out, in the notification database, and have the replay pass over them** - one message is left out; nothing else changes; the list is readable and reversible until the replay has passed the message.

## Decision

- **Option 3.** A table `skipped_dead_letter` holds `(partition, offset)` with a reason and a time. The replay reads the batch it is about to take, looks up which of those are listed, does not republish them, and moves the committed offset past them like any other message it has dealt with. It then removes their rows, because rows below the committed offset are never looked at again.
- **A second actuator endpoint, `deadletterskips`,** on the management port, which is never exposed publicly (ADR-0010), like the replay tool. `POST {partition, offset, reason}` lists a message to skip; `GET` shows what is listed; `DELETE ?partition=&offset=` takes one off again.
- **Only a message that is actually waiting can be skipped.** The offset must be at or after the committed offset and before the end of that partition. A typo, or an offset that was already replayed, is refused with 400 and stores nothing, so the list cannot fill with junk or quietly mean something else later.
- **A reason is required** (1 to 200 characters) and any email address in it is hidden before it is stored, like the reasons shown by the replay listing. The skip is a person's decision and the reason is how the next person understands it.
- **Skipping twice is the same as skipping once.**
- **The listing shows which parked messages are skipped,** so an operator sees what the next replay will leave out. It still never shows a message body.
- **The message stays on the topic** (retention applies as before); only the replay stops taking it. A skip can be undone any time before a replay has passed the message, with `DELETE`, and a message already passed is gone from the replay's point of view like any replayed one.
- **A counter,** `skyhub_notifications_skipped_total`, counts messages a replay passed over, and a log line carries the partition, offset and reason.

## Consequences

- Positive: a message that can never succeed stops costing every replay a failure, and the replay stays one command.
- Positive: nothing is destroyed; the decision is visible, reasoned and reversible until the replay moves on.
- Positive: no new infrastructure: one table and one more actuator endpoint.
- Negative: **there is no record of who skipped it.** The management port has no login (ADR-0032), so the reason is the only trace. A shared operations tool would need one; the access to that port is the control.
- Negative: **offsets are only unique within the dead-letter topic as it is now.** If that topic is deleted and recreated, a skip row left from before could match a different message. Rows are removed when the replay passes them, which keeps this small, but a row for a message that is never replayed stays.
- Negative: the replay reads the skip list once per batch (one query per partition in the batch), so a replay of 1,000 messages costs a handful of queries, not 1,000.
- Negative: a skipped message still counts in the "parked" total until a replay passes it.
- Follow-up: a person's identity on management-port actions; expiring old skip rows.
