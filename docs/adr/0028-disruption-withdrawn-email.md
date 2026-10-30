# ADR-0028: Tell travellers when a disruption is withdrawn

**Status:** Accepted
**Date:** 2026-10-30

## Context

A traveller emailed that their flight is cancelled (ADR-0020) may rebook elsewhere or cancel. When staff withdraw the disruption (ADR-0026), their booking page quietly stops showing the notice, but nobody tells them the flight is operating. They might still be arranging another trip.

## Decision

- A new event, `booking.disruption-withdrawn`, carries the locator, address, flight, date and what was withdrawn (DELAYED or CANCELLED). Its shape is `api-specs/events/booking-disruption-withdrawn.v1.example.json`, read by the tests of both services.
- The booking service writes **one event per booking still active on the flight**, in the same transaction as the withdrawal (the outbox, ADR-0019). A withdrawal that fails sends nothing; one that succeeds cannot lose the emails.
- It follows the recipe of ADR-0020: a record, a parser rule, a listener case, a service method and one email text. The notification service's delivery rules (one email per event id, dead-letter on failure) apply unchanged.
- The text differs by what was withdrawn. For a cancellation it says the flight is operating and that **a booking the traveller has already cancelled stays cancelled and keeps its refund**. For a delay it says the original time applies again.
- A second withdrawal request finds nothing and answers 404, so a retry cannot email anyone twice.

## Consequences

- Positive: travellers who were told something was wrong are told it is fine, in the same way.
- Positive: no new infrastructure; the cost is one outbox row per active booking, like recording.
- Negative: **travellers who already cancelled are not emailed** (they are no longer active), and may not know the flight operates. Their refund is unaffected.
- Negative: adding an event type still touches several places (the sealed event type, the parser, the listener, the service). That is the price of a closed, checked list of events; a registry would remove it if the list keeps growing.
- Negative: a traveller who rebooked themselves because of the cancellation gets a "good news" email they may not need.
