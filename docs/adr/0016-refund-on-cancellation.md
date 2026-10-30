# ADR-0016: Refund on cancellation by simple fare rules

**Status:** Accepted
**Date:** 2026-10-30

## Context

A traveller who cancels a paid booking (ADR-0015) is still charged. The `refund` port already exists. What is missing is the rule for *when* money goes back, and how to give it back safely when the payment provider can be slow or down.

Real fare rules are complicated (fare families, fees, taxes, partial refunds). SkyHub needs a rule that is simple, fair and easy to explain, and a design that lets richer rules replace it later.

## Options considered

1. **Always refund in full** — simplest, but a traveller could cancel at the gate and the airline would have lost the seat for nothing.
2. **Time-based, all or nothing** — full refund if cancelled early enough, none if late; full refund whenever the airline cancels a flight on the booking.
3. **Tiered / partial refunds with fees** — closer to real fares, but the provider port refunds a charge in full only, and the fee schedule is invented data.

## Decision

Option 2.

- **The airline cancelled a flight on this booking** (a `CANCELLED` disruption on any segment, ADR-0014): refund in full, whenever the traveller cancels.
- **Otherwise, 24 hours or more before the first departure**: refund in full. Departure is the revised time if the flight is delayed (disruption beats timetable, as in check-in).
- **Otherwise (less than 24 hours before, or already departed)**: no refund.
- The rule is a pure class, `RefundPolicy`, with no database or clock inside, so it is tested with plain inputs and can be replaced by a richer policy without touching the cancel flow.

**Order of work when a booking is cancelled** (same shape as ADR-0015, in reverse):

1. Decide the refund (needs the flight catalog, a network call): done first, so if flight-search is down the cancel fails with 503 and *nothing* has changed.
2. One database transaction: cancel the booking, release the seats, and set the payment to `REFUND_DUE` or `NO_REFUND`.
3. Outside any transaction: ask the provider to refund the charge, then mark the payment `REFUNDED`.

**If the provider fails in step 3**, the booking is still cancelled and the payment stays `REFUND_DUE`. The response says the refund is pending. Cancelling again (already idempotent) retries the refund, so no new endpoint is needed. The failure is logged at ERROR with the charge id, counted, and alerted on (`CancellationRefundStuck`, with a runbook).

The response carries `refund: { status, amount, currency }` with status `REFUNDED`, `PENDING` or `NOT_REFUNDABLE`. A booking with no payment record (made before payments existed) has no refund object.

## Consequences

- Positive: the traveller always learns what they get back; no money is lost to a provider outage; no booking stays half-cancelled.
- Positive: the policy is one small class, replaceable.
- Negative: all-or-nothing is harsh at the 24-hour edge; partial refunds and fees are not modelled.
- Negative: a stuck `REFUND_DUE` is retried only when someone cancels again, or by hand. An automatic retry job is follow-up work.
- Follow-up: tiered refunds (needs a partial `refund(chargeId, amount)` on the port); a job that retries `REFUND_DUE`; refund email once notifications exist; refunding a single passenger.
