# ADR-0015: Pay when booking: charge first, book second, refund if booking fails

**Status:** Accepted
**Date:** 2026-10-30

## Context

A booking is currently free. A real airline takes payment, and payment is the hard part of booking: it crosses a network boundary we do not control, can be slow or fail halfway, and must never charge a traveller twice or charge them for a booking that did not happen. A database transaction cannot span "charge a card" and "reserve a seat".

SkyHub has no real payment provider. The design must therefore put the provider behind a port, so a mock now and a real one later (Stripe, Adyen, a bank gateway) differ only in an adapter.

## Options considered

1. **Book first, charge after** — a traveller can hold seats they never pay for; failed payments then need a release step and a way to tell the traveller. Seats are the scarce thing here, so this wastes them.
2. **Charge first, then book, and refund if booking fails (compensation)** — seats are only ever booked for paying travellers. The failure case (paid, but the seat went) is rare, because the seats were held for 10 minutes (ADR-0012), and it has a clear fix: give the money back.
3. **A two-phase flow with a `PENDING_PAYMENT` booking and a webhook from the provider** — the shape real providers use for card redirects (3-D Secure), but it needs new booking states, a public webhook endpoint and signature checking. Worth building when a real provider arrives.

## Decision

Option 2.

- **Price is decided by the server.** The booking service adds up the fare of each flight (from flight-search, via the existing cached catalog) times the number of passengers. The browser may send `expectedAmount`; if it differs from the server's price the booking is refused with 409 ("price changed") and nothing is charged. A client can never choose what it pays. `POST /quotes` gives the website the same number to show.
- **No card data touches SkyHub.** The request carries a `paymentToken` that a payment provider issued to the browser. The mock provider accepts three: `tok_visa` (approved), `tok_declined` (declined) and `tok_provider_down` (provider outage).
- **`PaymentGateway` is a port** owned by the application layer, with `charge(token, amount, currency, chargeKey)` and `refund(chargeId)`. The mock adapter lives in `integration`.
- **Order of work.** Replay check, validation, price check, then the **charge**, outside any database transaction (it is a network call), then the **booking transaction**, which also stores a `payment` record. If anything in the booking fails, the charge is **refunded** before the error is returned.
- **Charging twice is impossible.** The charge key is derived from the request's `Idempotency-Key`. The provider returns the same charge for the same key, so a retry after a crash between "charged" and "booked" does not charge again; it finds the charge and finishes the booking. A replayed booking is answered from the stored booking and never reaches the provider.
- **Failure answers.** Declined → 402. Provider unreachable → 503 with `Retry-After`, nothing charged, nothing booked. Seat gone after charging → refunded, then 409 as before.
- **If the refund itself fails**, the money is taken and there is no booking. That case is logged at ERROR with the charge id, counted in `skyhub_payments_total{outcome="refund_failed"}`, and pages (alert + runbook). Automated reconciliation is the follow-up.

## Consequences

- Positive: seats are only booked for paid travellers; no double charge; the provider can be replaced by writing one adapter.
- Positive: the money path is observable: captured, declined, refunded, refund_failed.
- Negative: charge-then-book can briefly take money for a booking that then fails; the refund fixes it, but a traveller may see a pending charge for a few days on a real card. Option 3 avoids it.
- Negative: **cancelling a booking does not refund yet.** Refund rules (fees, fare conditions) are a product decision; the port already supports `refund`.
- Follow-up: refund on cancellation by fare rules; reconcile charges with no booking; 3-D Secure / webhook flow (option 3); taxes, ancillaries and multiple currencies in one booking (today a booking must be in a single currency).
