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
| [0033](0033-traveller-accounts.md) | Traveller accounts: sign up and sign in with an emailed link | Accepted |
| [0034](0034-my-bookings.md) | My bookings: a signed-in traveller sees the bookings made with their address | Accepted |
| [0035](0035-bulk-rebooking.md) | Moving many bookings in one request | Accepted |
| [0036](0036-rebooking-keeps-connections.md) | A rebooking must not break the traveller's connections | Accepted |
| [0037](0037-skipping-a-parked-notification.md) | Skipping a parked notification that can never succeed | Accepted |
| [0038](0038-paging-my-bookings.md) | Paging "Your bookings" past the newest 50 | Accepted |
| [0039](0039-changing-the-account-email.md) | Changing the account's email address | Accepted |
| [0040](0040-telling-the-old-address.md) | Telling the old address when an account moves | Accepted |
| [0041](0041-ending-sessions-when-credentials-change.md) | Ending sessions when an account's credentials change | Accepted |
| [0042](0042-filtering-my-bookings.md) | Filtering "Your bookings" into upcoming and past | Accepted |
| [0043](0043-grouping-proposals-on-the-operations-page.md) | Grouping and finding proposals on the operations page | Accepted |
| [0044](0044-upcoming-bookings-soonest-first.md) | Upcoming bookings, soonest flight first | Accepted |
| [0045](0045-one-login-lock-and-password-gate.md) | One login lock and one password gate for staff and traveller accounts | Accepted |
| [0046](0046-sealing-link-tokens-on-the-topic.md) | Sealing one-time link tokens on the topic | Accepted |
| [0047](0047-sign-out-everywhere.md) | Signing out everywhere | Accepted |
| [0048](0048-one-password-rule-and-the-72-byte-limit.md) | One password rule, and the 72-byte limit | Accepted |
| [0049](0049-proving-the-real-chain-live.md) | Proving the real chain live (account and booking journeys) | Accepted |
| [0050](0050-html-email-beside-the-text.md) | HTML email beside the plain text | Accepted |
| [0051](0051-past-bookings-most-recently-flown-first.md) | Past bookings, most recently flown first | Accepted |
| [0052](0052-who-ran-a-management-port-action.md) | Who ran a management-port action | Accepted |
| [0053](0053-one-command-live-check.md) | One command for the whole live check | Accepted |
| [0054](0054-taking-an-account-back.md) | Taking an account back after it was moved without its owner | Accepted |
| [0055](0055-checking-out.md) | Checking out, so the seats can change again | Accepted |
| [0056](0056-checking-a-boarding-pass-against-the-booking.md) | Checking a boarding pass against the booking | Accepted |
| [0057](0057-signing-out-of-a-booking.md) | Signing out of a booking ends every session of it | Accepted |
| [0058](0058-checking-in-one-passenger-at-a-time.md) | Checking in one passenger at a time | Accepted |
| [0059](0059-reaching-check-in-in-the-live-check.md) | Reaching check-in in the live check | Accepted |
| [0060](0060-boarding-and-the-gate-agent.md) | Boarding, and a role that can only work the gate | Accepted |
| [0061](0061-browser-coverage-on-the-live-stack.md) | Browser coverage on the live stack | Accepted |
| [0062](0062-the-gate-screen.md) | The gate screen | Accepted |
| [0063](0063-the-boarding-window.md) | The boarding window | Accepted |
| [0064](0064-undoing-a-boarding.md) | Undoing a wrong boarding | Accepted |
| [0065](0065-who-has-boarded-a-flight.md) | Who has boarded a flight | Accepted |
| [0066](0066-non-blocking-retry-topics.md) | Non-blocking retry topics for notifications | Accepted |
| [0067](0067-filtering-proposals-by-outcome.md) | Filtering proposals by what happened to them | Accepted |
| [0068](0068-windowing-the-proposal-list.md) | Drawing only part of a long proposal list | Accepted |
| [0069](0069-moving-checked-in-travellers.md) | Moving travellers who are checked in | Accepted |
| [0070](0070-telling-a-moved-traveller-to-check-in-again.md) | Telling a moved traveller to check in again | Accepted |
| [0071](0071-a-qr-code-on-the-boarding-pass.md) | A QR code on the boarding pass | Accepted |
| [0072](0072-scanning-a-boarding-pass-with-the-camera.md) | Scanning a boarding pass with the phone's camera | Accepted |
| [0073](0073-check-in-confirmation-email.md) | A confirmation email when someone checks in | Accepted |
| [0074](0074-boarding-passes-attached-to-the-check-in-email.md) | The boarding passes attached to the check-in email | Accepted |
| [0075](0075-loose-ends-from-the-check-in-work.md) | Three loose ends from the check-in work | Accepted |
| [0076](0076-boarding-a-late-passenger.md) | Boarding a passenger after the gate has closed | Accepted |
| [0077](0077-a-boarding-window-for-a-route.md) | A boarding window for a route | Accepted |
| [0078](0078-saved-passengers.md) | Saved passengers | Accepted |
| [0079](0079-nextjs-tracing.md) | Tracing the website | Accepted |
| [0080](0080-first-live-run-of-the-language-model.md) | The first live run of the language model | Accepted |
| [0081](0081-service-images.md) | Container images for the Java services | Accepted |
| [0082](0082-agent-and-website-images.md) | Container images for the disruption agent and the website | Accepted |
| [0083](0083-the-whole-stack-in-containers.md) | The whole stack in containers | Accepted |
