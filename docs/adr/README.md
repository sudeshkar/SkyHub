# Architecture Decision Records

An ADR records one significant decision: the context, the decision, and its consequences. Copy [`0000-template.md`](0000-template.md) for each new one. Never edit an accepted ADR — supersede it with a new one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-multi-hub-configuration.md) | Hubs and inter-airport transfers are configuration | Accepted |
| [0002](0002-optimistic-locking-for-seats.md) | Optimistic locking for seat inventory | Accepted |
| [0003](0003-database-per-service.md) | Database per service | Accepted |
| [0004](0004-store-schedules-derive-dated-flights.md) | Store schedules, derive dated flights | Accepted |
| [0005](0005-all-times-in-utc.md) | All times in UTC | Accepted |
| [0006](0006-in-memory-timetable-instead-of-result-cache.md) | In-memory timetable index instead of a search result cache | Accepted |
| [0007](0007-rest-for-queries-events-for-side-effects.md) | REST for queries, events for side effects | Accepted |
| [0008](0008-idempotency-key-on-post-pnrs.md) | `Idempotency-Key` on `POST /pnrs` | Accepted |
| [0009](0009-rate-limiting-at-the-edge.md) | Rate limiting at the edge with a sliding window counter | Accepted |
| [0010](0010-encrypt-personal-data-at-rest.md) | Encrypt personal data at rest; hash what is only compared | Accepted |
| [0011](0011-manage-booking-with-a-short-lived-token.md) | Manage a booking with a short-lived, booking-scoped token | Accepted |
| [0012](0012-seat-holds-that-expire.md) | Seat holds that expire | Accepted |
| [0013](0013-online-check-in-and-boarding-passes.md) | Online check-in and signed boarding passes | Accepted |
| [0014](0014-flight-disruptions.md) | Flight disruptions: delays and cancellations | Accepted |
| [0015](0015-payment-before-booking-with-compensation.md) | Pay when booking: charge first, book second, refund if booking fails | Accepted |
| [0016](0016-refund-on-cancellation.md) | Refund on cancellation by simple fare rules | Accepted |
