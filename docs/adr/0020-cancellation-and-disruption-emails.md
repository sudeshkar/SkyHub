# ADR-0020: Cancellation and disruption emails

**Status:** Accepted
**Date:** 2026-10-30

## Context

With the outbox and Kafka in place (ADR-0019), two more things should reach travellers without them opening their booking: that they cancelled and what happens to their money (ADR-0016), and that a flight on their booking was delayed or cancelled (ADR-0014). Each raises a question the confirmation email did not.

- **Disruptions touch many bookings at once.** One request from operations affects every active booking on the flight. Who is written to, and what stops an operations tool that retries from emailing hundreds of people twice?
- **The refund is not finished when the traveller cancels.** The cancel commits first and the provider is paid afterwards (ADR-0016), and the payment can fail and stay pending. An email sent at cancellation time cannot honestly say "refunded".

## Options considered

For disruptions:
1. **One event per flight, the notification service looks up the bookings** — it would need to read the booking database or call a booking endpoint, a new coupling and a credential.
2. **One event per affected booking, written by the booking service in the transaction that records the disruption** — booking already knows the bookings and their contact addresses; the notification service stays a pure consumer.

For the refund email:
1. **Email when the money has moved** — accurate, but the money may move minutes later or after a retry, and "no refund due" has no moment of its own.
2. **Email at cancellation, describing what the fare rules decided** — "we are refunding X" or "this booking is not refundable".

## Decision

- **Disruptions: one `booking.disrupted` event per active booking, in the same transaction as the disruption record.** Either the record and all its events exist, or none do. Cancelled bookings get nothing. Event ids are random per event; the notification service is idempotent on them (ADR-0019).
- **Recording the same disruption again sends nothing.** If the type, the new time and the reason are all unchanged, the record is left as it is and no events are written, so a retrying tool cannot email everyone twice. A real change (a later new time, a delay becoming a cancellation) tells everyone again. The reply to operations still reports how many bookings are on the flight.
- **Cancellation: one `booking.cancelled` event in the cancel transaction, saying what was decided, not what has happened.** It carries `FULL_REFUND` or `NO_REFUND` with the amount paid; it is left out for a booking that was never paid. The email says "we are refunding X to your original payment method" and never "has been refunded". It is written only by the cancel that changed something, so pressing Cancel again (the retry for a pending refund) sends no second email.
- **What the emails promise is exactly what the rules do.** A flight the airline cancelled is always refunded in full, whenever the traveller cancels. For a delay the normal 24-hour rule applies, counted from the new departure time. The wording is tested against these rules.
- **Times in emails are UTC and labelled.** The website also shows UTC (ADR-0005).

## Consequences

- Positive: travellers learn of a delay or cancellation without opening their booking, and a retrying operations tool is harmless.
- Positive: the notification service gained two event types and two email templates and nothing else; it still knows no booking data beyond the events.
- Negative: the booking service now loads every active booking on the flight when a disruption changes, and writes one outbox row each. For a few hundred passengers that is fine; for thousands the work should move to a background job, and the transaction should not hold them all.
- Negative: someone who is told "we are refunding you" and whose refund then fails (ADR-0016's pending state) is not told about the failure by email. The `CancellationRefundFailed` alert is how a person finds out, and they can press Cancel again from the booking page.
- Negative: no email when a delay is *reversed* (recording the original time again counts as a change and tells everyone the new, original, time, which is correct but may read oddly).
- Follow-up: a refund-completed email once refunds have their own event; check-in-opening reminders; SMS; batching for very large flights.
