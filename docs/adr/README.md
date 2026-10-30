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
| [0017](0017-staff-login-and-roles.md) | Staff login and roles instead of a shared operations key | Accepted |
| [0018](0018-ops-screen-through-the-website-server.md) | The operations screen is a page on the website, and its token stays on the server | Accepted |
| [0019](0019-transactional-outbox-and-kafka.md) | Notifications through a transactional outbox and Kafka | Accepted |
| [0020](0020-cancellation-and-disruption-emails.md) | Cancellation and disruption emails | Accepted |
| [0021](0021-smtp-email-and-mailpit.md) | Send email over SMTP, with Mailpit as the local mail server | Accepted |
| [0022](0022-disruption-agent-proposes-code-verifies.md) | The disruption agent proposes, code verifies, a person decides | Accepted |
| [0023](0023-staff-rebooking-after-a-disruption.md) | Staff move bookings off a disrupted flight, and the service checks the rules again | Accepted |
| [0024](0024-approval-screen-on-the-operations-page.md) | Approving the agent's proposals on the operations page | Accepted |
| [0025](0025-cancelled-flights-are-not-sold-and-search-follows-disruptions.md) | Cancelled flights are not sold, and search follows disruptions | Accepted |
| [0026](0026-withdrawing-a-disruption.md) | Withdrawing a disruption | Accepted |
| [0027](0027-connections-follow-delays.md) | Connections follow delays | Accepted |
| [0028](0028-disruption-withdrawn-email.md) | Tell travellers when a disruption is withdrawn | Accepted |
| [0029](0029-check-in-reminders.md) | Check-in-open reminders | Accepted |
| [0030](0030-refund-retries.md) | Refunds that failed are retried, not forgotten | Accepted |
| [0031](0031-refund-completed-email.md) | Tell travellers when a delayed refund is paid | Accepted |
| [0032](0032-dead-letter-replay.md) | Putting parked notifications back | Accepted |
