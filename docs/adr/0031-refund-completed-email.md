# ADR-0031: Tell travellers when a delayed refund is paid

**Status:** Accepted
**Date:** 2026-10-31

## Context

When a traveller cancels, the cancellation email says "we are refunding X" (ADR-0020). If the payment provider did not confirm at that moment, the refund stays pending and the retry job pays it later (ADR-0030), perhaps a day later. The traveller sees "pending" on the booking page and hears nothing when it is done.

## Decision

- A new event, `booking.refund-completed`, carries the locator, address, amount and currency. Its shape is a shared example read by both services' tests. One email text.
- It is written **only by the retry job, when it moves a pending cancellation refund to paid**, in the same transaction as the status change. The status change happens once, so the email is queued once.
- It is **not** written when the refund is paid at once (the cancellation email already says so), and **not** when the traveller pays it themselves by pressing Cancel again (they are looking at the result). If the traveller pressed Cancel again before the job ran, the job finds nothing owed and says nothing.
- The amount must be positive: the parser refuses an event with no amount, so an email never says "0".

## Consequences

- Positive: a delayed refund is announced, so the traveller is not left wondering.
- Positive: the same recipe as the earlier emails; no new infrastructure.
- Negative: **a charge for a failed booking is not announced.** That traveller never got a booking and the record has no email address. They may not know they were charged and refunded. Recording the contact address on the charge (when we have one) would allow it.
- Negative: no email at all if the refund is paid by the traveller pressing Cancel again.
- Negative: the amount is the booking's whole payment; partial refunds do not exist yet.
