# Architecture Decision Records

An ADR records one significant decision: the context, the decision, and its consequences. Copy [`0000-template.md`](0000-template.md) for each new one. Never edit an accepted ADR — supersede it with a new one.

| # | Decision | Status |
|---|---|---|
| [0001](0001-multi-hub-configuration.md) | Hubs and inter-airport transfers are configuration | Accepted |
| [0002](0002-optimistic-locking-for-seats.md) | Optimistic locking for seat inventory | Accepted |
| 0003 | Database per service | ✍️ To write |
| 0004 | Store schedules, derive dated flights | ✍️ To write |
| 0005 | All times in UTC | ✍️ To write |
| 0006 | Cache search results in Redis with a 5-minute TTL | ✍️ To write |
| 0007 | REST for queries, events for side effects | ✍️ To write |
| 0008 | `Idempotency-Key` on `POST /pnrs` | ✍️ To write |
