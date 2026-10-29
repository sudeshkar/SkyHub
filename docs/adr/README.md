# Architecture Decision Records

An ADR records one significant decision: the context, the decision, and its consequences. Copy [`0000-template.md`](0000-template.md) for each new one. Never edit an accepted ADR — supersede it with a new one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-multi-hub-configuration.md) | Hubs and inter-airport transfers are configuration | Accepted |
| [0002](0002-optimistic-locking-for-seats.md) | Optimistic locking for seat inventory | Accepted |
| 0003 | Database per service | ✍️ To write |
| 0004 | Store schedules, derive dated flights | ✍️ To write |
| 0005 | All times in UTC | ✍️ To write |
| [0006](0006-in-memory-timetable-instead-of-result-cache.md) | In-memory timetable index instead of a search result cache | Accepted |
| 0007 | REST for queries, events for side effects | ✍️ To write |
| 0008 | `Idempotency-Key` on `POST /pnrs` | ✍️ To write |
| [0009](0009-rate-limiting-at-the-edge.md) | Rate limiting at the edge with a sliding window counter | Accepted |
| [0010](0010-encrypt-personal-data-at-rest.md) | Encrypt personal data at rest; hash what is only compared | Accepted |
| [0011](0011-manage-booking-with-a-short-lived-token.md) | Manage a booking with a short-lived, booking-scoped token | Accepted |
| [0012](0012-seat-holds-that-expire.md) | Seat holds that expire | Accepted |
| [0013](0013-online-check-in-and-boarding-passes.md) | Online check-in and signed boarding passes | Accepted |
| [0014](0014-flight-disruptions.md) | Flight disruptions: delays and cancellations | Accepted |
