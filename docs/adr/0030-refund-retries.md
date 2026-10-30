# ADR-0030: Refunds that failed are retried, not forgotten

**Status:** Accepted
**Date:** 2026-10-30

## Context

Two kinds of refund could fail and then wait for a person (ADR-0015, ADR-0016):

1. **A charge for a booking that failed.** A traveller is charged, the booking fails (the seat was taken), the refund also fails. The charge's reference existed **only in a log line**. A person had to find it and refund it in the provider's dashboard.
2. **A refund owed after cancellation.** The booking is cancelled and the money is owed, but the provider did not confirm. It stayed pending until the traveller pressed Cancel again.

Both are money owed to a traveller, and both were forgotten unless someone looked.

## Options considered

1. **Retry inline, in the request** - holds the traveller's request open for an unavailable provider.
2. **A retry job that scans for owed refunds** - independent of requests; needs somewhere durable to find them.
3. **A queue with delayed messages** - more infrastructure for a few rows.

## Decision

- **A durable record first.** A charge whose refund failed after a failed booking is written to a new table, `unrefunded_charge`, in its own statement (the booking transaction has already rolled back). Refunds owed after cancellation are already durable: the payment row is `REFUND_DUE`.
- **One job, many sources.** `RefundRetrier` knows nothing about where a refund comes from. Each kind is a `RefundSource` implementation that Spring collects: a third kind of owed refund is a new class, not a change to the job. It runs once a minute.
- **Backoff, then give up loudly.** After each failure the wait doubles from 5 minutes, up to 4 hours; after 12 attempts (about 29 hours) the job stops and counts `gave_up`. That alert pages, because only a person can fix it, and the runbook says how to reset the row or refund by hand.
- **Safe with several instances and with a manual path.** A source claims rows with `FOR UPDATE SKIP LOCKED` and schedules each row's next attempt in advance, so two instances never take the same row and a crash costs only a repeat. The provider's refund is safe to repeat, and a payment moves to `REFUNDED` once, so the job and a traveller pressing Cancel again can run together.
- **Bounded work.** 50 refunds per source per sweep; a partial index holds only unfinished rows, so looking costs the same however many refunds were ever made. A source that cannot be read does not stop the others.
- **Monitoring:** `skyhub_refund_retries_total{source,outcome=refunded|failed|gave_up}` and the `RefundGaveUp` alert. The existing payment counters still count each refund once.

## Consequences

- Positive: the money comes back by itself in the normal case, and when it cannot, the alert names the charge.
- Positive: a new kind of owed refund costs one class.
- Negative: **a crash between charging and recording anything still loses the charge.** The record is written after the refund fails, not before the charge. Writing an intent first would close this; the charge key already lets a retried request find it.
- Negative: if even the insert fails (the database is down at that moment), the charge is only in the log. The log line says so and the runbook covers it.
- Negative: the job can refund a cancellation in the instant between the cancel transaction and the traveller's own refund; the provider call is repeated, harmlessly.
- Negative: refunds are sequential, so a provider that hangs slows a sweep; the next sweep continues.
- Negative: the traveller is not emailed that a delayed refund has now been paid (a "refund completed" email is on the notifications list).
