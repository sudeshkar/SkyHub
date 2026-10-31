# SkyHub Runbooks

One section per alert in `observability/prometheus/alerts.yml`. Each says what the alert means, how to confirm it, and how to mitigate. Start every investigation from the **SkyHub overview** dashboard in Grafana, then follow a `traceId` from a log line into Tempo.

## SearchErrorRateHigh

**Means:** more than 1% of search requests returned 5xx for 5 minutes.
**Confirm:** Overview → *Error rate (5xx)*. In Loki: `{service="flight-search", level="ERROR"}`; open the `traceId` of a failing request.
**Likely causes:** timetable failed to load at startup (no snapshot), a bad deploy, out of memory.
**Mitigate:** roll back the last deploy; check *Flights in timetable* is not 0; restart one instance at a time.

## SearchLatencyHigh

**Means:** search p99 above 200 ms for 10 minutes (SLO breach).
**Confirm:** Overview → *Latency p99 by endpoint*; *JVM heap used* for GC pressure.
**Likely causes:** CPU saturation, long GC pauses, an unusually large timetable.
**Mitigate:** scale out (search is stateless and needs no database); raise heap if GC-bound.

## BookingErrorRateHigh

**Means:** more than 2% of `POST /pnrs` returned 5xx for 5 minutes. A 409 (seat taken) is *not* an error.
**Confirm:** Overview → *Bookings per minute by outcome* and *Database connections*. Loki: `{service="booking", level="ERROR"}`.
**Likely causes:** database down or slow, flight-search unreachable (returns 503), encryption key misconfigured.
**Mitigate:** check PostgreSQL health and connection pool; see *FlightCatalogUnavailable*; never rotate `SKYHUB_PII_KEY` without the rotation procedure (ADR-0010).

## TimetableStale

**Means:** flight-search has not reloaded its timetable for over 15 minutes. Searches still work from the last snapshot.
**Confirm:** Overview → *Timetable age*; Loki: `{service="flight-search"} |= "Timetable refresh failed"`.
**Likely causes:** flight-search cannot reach its database.
**Mitigate:** restore database connectivity; the next scheduled reload recovers automatically.

## DatabaseConnectionsWaiting

**Means:** requests are queueing for a database connection — the earliest overload signal.
**Confirm:** Overview → *Database connections* (waiting > 0) and rising latency.
**Likely causes:** traffic spike, slow queries, connection leak.
**Mitigate:** scale out the service; check slow queries in PostgreSQL; do not simply raise the pool size above what the database can serve.

## FlightCatalogUnavailable

**Means:** more than 5% of booking → flight-search calls fail. Flights that already have seat rows keep working; new flight dates return 503.
**Confirm:** Overview → *Booking → flight-search calls* (`unavailable`); is flight-search up (*ServiceDown*)?
**Mitigate:** restore flight-search; no booking data is at risk (nothing is created while the check fails).

## BookingReferenceGuessing

**Means:** more than 12 failed "open my booking" attempts per minute for 5 minutes. Someone may be guessing booking references with common surnames. Real users mistype occasionally; this volume is not them.
**Confirm:** Overview → *Manage actions* (`session_denied` climbing against `session_opened`); in Loki: `{service="booking"} |= "Manage session denied"`.
**Mitigate:** the Next.js server already limits lookups to 10 per 15 minutes per client, so a flood means many clients: tighten that limit or block the source at the gateway. No booking is exposed by failed attempts (a wrong name and an unknown reference look identical). If `session_opened` also rises abnormally, treat it as a possible compromise and check recent `Manage session opened` logs.

## SeatHoldsAbandoned

**Means:** in the last hour more than 50 seat holds ended, and over 90% of the ones that ended (expired or became a booking) expired. Travellers pick seats and leave, or something stops them finishing. A held seat is unavailable to everyone else for 10 minutes, so this also hides inventory.
**Confirm:** Overview → *Seat holds per minute by outcome* (`expired` far above `converted`); compare with *Bookings per minute by outcome*: if bookings also fall, checkout is broken (look at `BookingErrorRateHigh` and the website's server logs); if bookings are normal and holds spike, suspect a script holding seats. Per client, holds are limited to 6 per minute at the website.
**Mitigate:** a broken checkout is fixed at its cause. For hold abuse, block the source at the gateway; holds free themselves after 10 minutes, and the cleanup job frees them within 30 seconds of that. To free seats immediately: `UPDATE seat_inventory SET status='AVAILABLE', hold_id=NULL, held_until=NULL, version=version+1 WHERE status='HELD';` (safe: a hold that is released this way simply cannot be converted).

## CheckInRefusedHigh

**Means:** more than 20 check-in requests in 15 minutes, over half refused because check-in is not open yet or has closed. Real travellers try a little early or late; this share points at wrong times.
**Confirm:** Overview → *Check-ins per minute by outcome*; in Loki the refusal text names the time: the window is 24 hours to 60 minutes before departure (`skyhub.booking.check-in.*`). Compare the departure time in the message with the flight in flight-search (`GET /api/v1/flights/{flight}/departures/{date}`): a wrong timetable or a changed window setting shifts every flight.
**Mitigate:** correct the timetable or the setting and restart booking (window values are read at startup). Nothing is written when check-in is refused, so no data needs repair.

## RefundFailed

**Means:** a booking failed *after* the traveller was charged (usually: the seat was taken in the meantime), and giving the money back failed too. A traveller has been charged and has no booking. This is the one payment failure that cannot fix itself.
**Confirm:** the booking log line `Refund failed after a failed booking; the traveller has been charged` carries `payment.charge`, the provider's charge reference. In Loki: `{service="booking"} |= "Refund failed"`. Overview → *Payments per minute by outcome* (`refund_failed`).
**Mitigate:** usually nothing: the charge is written to `unrefunded_charge` and the refund is retried automatically (ADR-0030), first after 5 minutes and then with a growing wait. Check `SELECT charge_id, amount, currency, attempts, next_attempt_at, refunded_at FROM unrefunded_charge ORDER BY created_at DESC;`. If it stays unrefunded, the RefundGaveUp alert fires. Only if the log also says `Could not record the unrefunded charge` is there no row: refund that `payment.charge` by hand in the provider's dashboard. There is no booking to cancel: no `payment` row exists for it. A crash between charging and booking leaves a charge nobody recorded; that gap is described in ADR-0030.

## CancellationRefundFailed

**Means:** a traveller cancelled a paid booking that qualifies for a refund (ADR-0016), the booking was cancelled, but the payment provider did not confirm the refund. The traveller is owed money and sees the refund as *pending*. Unlike RefundFailed, nothing is wrongly booked: the seats are free and the booking is cancelled.
**Confirm:** Loki `{service="booking"} |= "Refund after cancellation failed"` (carries `pnr.locator` and `payment.charge`). Overview → *Payments per minute by outcome* (`cancel_refund_failed`). Payments still owed: `SELECT locator, charge_id, amount, currency FROM payment WHERE status = 'REFUND_DUE';` in the booking database.
**Mitigate:** check the provider is reachable (see PaymentProviderUnavailable). Cancelling the booking again retries the refund (it is safe to repeat), so ask the traveller to press *Cancel* again from the manage page, or refund the `charge_id` by hand in the provider's dashboard and then set the row to `REFUNDED`. The refund job retries it by itself (ADR-0030); if it keeps failing, RefundGaveUp fires.

## StaffLoginGuessing

**Means:** more than 10 staff logins failed or were refused for a locked account in 10 minutes (ADR-0017). Either someone is guessing passwords, or a real colleague has forgotten theirs. Five wrong passwords lock an account for 15 minutes, so a guesser is slowed to about 20 tries an hour per account.
**Confirm:** Loki `{service="booking"} |= "Staff login"` (each line carries `staff.username`). Many different usernames from a burst is guessing; one username repeating is usually a person. Overview metrics: `skyhub_staff_logins_total` by `outcome` (`failed`, `locked`).
**Mitigate:** if it is a colleague, wait 15 minutes or ask an administrator to create them a new account (there is no password reset yet). If it is guessing, check the edge rate limit is on (ADR-0009) and block the source address there. A locked account unlocks itself; nothing was accessed unless a `Staff logged in` line follows a run of failures for the same name, in which case treat that account as compromised and remove it from the database (`DELETE FROM staff_account WHERE username = '...'`); its token still works for up to 30 minutes.

## PaymentProviderUnavailable

**Means:** more than half of payment attempts in the last 10 minutes could not reach the payment provider. Nobody is charged and nobody is booked while this lasts: travellers see "payment provider unavailable" and can retry.
**Confirm:** Overview → *Payments per minute by outcome* (`unavailable` far above `captured`); the provider's status page; `Payment provider unavailable` warnings in Loki.
**Mitigate:** nothing in SkyHub to fix if the provider is down; wait for it and tell support. If only SkyHub cannot reach it, check outbound network access and the provider credentials. A retry with the same Idempotency-Key is safe: the provider never charges one key twice.

## OutboxBacklog

**Means:** at least one booking event has sat unpublished in the booking database for 10 minutes (ADR-0019). Bookings still succeed, but the events that trigger emails are not reaching Kafka, so travellers are not told.
**Confirm:** Overview metric `skyhub_outbox_pending` (should sit at 0 and spike for seconds) and `skyhub_outbox_publish_failures_total`. Loki `{service="booking"} |= "Event not published"` shows the broker error. The rows: `SELECT id, aggregate_id, event_type, created_at FROM outbox_event WHERE published_at IS NULL ORDER BY seq;` in the booking database.
**Mitigate:** check the broker is up (`docker compose ps kafka`, or the cluster's health) and reachable from booking (`KAFKA_BOOTSTRAP_SERVERS`). Nothing is lost: the relay retries every 2 seconds and publishes in order once the broker answers, so the backlog drains by itself. A single event stuck while others pass cannot happen (the relay stops at the first failure), so if `pending` stays flat the broker is the cause. Events older than a week are purged only after they are published.

## NotificationDeadLettered

**Means:** the notification service could not handle a message after its retries and copied it to the dead-letter topic `skyhub.booking.events.DLT` (ADR-0019). Usually the mail provider was down for longer than the retries, or a message is malformed. The traveller did not get that email.
**Confirm:** Loki `{service="notification"} |= "Email not sent"` (carries `pnr.locator` and `event.type`), and `skyhub_notifications_total{outcome="failed"}`. Read the parked messages: `kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic skyhub.booking.events.DLT --from-beginning` (the headers say why).
**Mitigate:** fix the cause (mail provider, bad message). Locally, `docker compose ps mailpit`; a mail outage longer than about six seconds ends here by design (ADR-0021). Then put the messages back with the replay tool on the notification service's management port (ADR-0032): `curl localhost:9083/actuator/deadletters` lists what is parked (kind of event, partition, offset, reason; never the body), and `curl -X POST localhost:9083/actuator/deadletters -H 'Content-Type: application/json' -d '{"limit": 100}'` puts up to that many back (default 100, at most 1000 per call). It is safe to run twice: a replay takes only what it has not taken before, and the consumer skips any event it has already recorded as sent. A message that fails again is parked again at a new place and the next replay takes it. Sign-up, reset and address-change links that are parked with "The link token could not be opened" (or "SKYHUB_LINK_TOKEN_KEY is not set") mean the notification service does not have the same `SKYHUB_LINK_TOKEN_KEY` as the booking service (ADR-0046): fix the key, then replay; those sealed before a key change cannot be opened with the new one and are better skipped, since their links expire in an hour. A message that can never succeed (malformed, or an event type that no longer exists) can be left out of every replay: find its `partition` and `offset` in the listing, then `curl -X POST localhost:9083/actuator/deadletterskips -H 'Content-Type: application/json' -d '{"partition": 0, "offset": 12, "reason": "why it can never succeed"}'` (a reason is required; the offset must be a message that is still waiting). `curl localhost:9083/actuator/deadletterskips` shows what is left out, the parked listing marks those messages `skipped`, and `curl -X DELETE 'localhost:9083/actuator/deadletterskips?partition=0&offset=12'` takes one off again before the next replay passes it (ADR-0037). The replay passes over skipped messages without sending them and counts them in `skipped`; `skyhub_notifications_skipped_total` counts them over time. Nothing records who skipped a message, so write the reason for the next person. Do not replay before the cause is fixed. Message bodies stay on the topic after a replay (nothing is deleted).

## FlightDisruptionEventsUnreadable

**Means:** flight search read a message on `skyhub.flight.disruptions` that it could not understand and skipped it (ADR-0025). If it was a cancellation, search may still offer that flight. Booking will still refuse to sell it, so nobody is charged; travellers just see a flight they cannot buy.
**Confirm:** Loki `{service="flight-search"} |= "Skipped a flight disruption message"` (the reason is in the line). `skyhub_flight_status_events_total{outcome="invalid"}`. Read the topic: `kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic skyhub.flight.disruptions --from-beginning`.
**Mitigate:** usually a booking and search version mismatch (a new field search rejects) or a bad manual publish. Fix the producer or consumer; record the disruption again (any change republishes it) so search hears it. If a search instance has been down or cut off from Kafka, restart it: it rebuilds from the start of the topic. Disruptions recorded before the topic existed need the one-off backfill: start booking once with `SKYHUB_OUTBOX_BACKFILL_FLIGHT_EVENTS=true`.

## CheckInRemindersFailing

**Means:** the sweep that queues "check-in is open" emails (ADR-0029) failed more than twice in 30 minutes. Travellers are not told their check-in opened. They can still check in; only the reminder is late. Nothing is lost: a booking is reminded on the first sweep that works, as long as check-in is still open.
**Confirm:** Loki `{service="booking"} |= "Check-in reminder sweep failed"` (the cause is in the stack trace; usually the database). `skyhub_checkin_reminders_total{outcome="failed"}`. A related counter, `outcome="flight_unavailable"`, rising means flight search could not be asked a flight's time, so that flight is skipped until it answers.
**Mitigate:** fix the cause (database or flight search). The sweep runs every 5 minutes and catches up by itself. To see who is still waiting: bookings with no row in `check_in_reminder` for a flight whose check-in is open. A reminder that was never sent after check-in closed is not sent late.

## RefundGaveUp

**Means:** the refund job tried a refund 12 times over about a day and the provider refused or did not answer every time (ADR-0030). A traveller is owed money. Nothing will retry it again.
**Confirm:** Loki `{service="booking"} |= "Gave up refunding a charge"` (carries `refund.source`, `payment.charge`, and the provider's error). `skyhub_refund_retries_total{outcome="gave_up"}` by `source`. For `failed-booking`: `SELECT * FROM unrefunded_charge WHERE refunded_at IS NULL AND attempts >= 12;`. For `cancellation`: `SELECT locator, charge_id, amount, currency FROM payment WHERE status = 'REFUND_DUE' AND refund_attempts >= 12;`.
**Mitigate:** find out why the provider refuses that charge (already refunded outside our system? expired? wrong id?). If it is fixable, reset the row so the job tries again: `UPDATE unrefunded_charge SET attempts = 0, next_attempt_at = now() WHERE charge_id = '...';` (or `UPDATE payment SET refund_attempts = 0, refund_next_attempt_at = NULL WHERE locator = '...';`). Otherwise refund it by hand in the provider's dashboard, then set `refunded_at = now()` (or `status = 'REFUNDED'`) so it is not found again.

## An account address change that did not complete (no alert)

**Means:** a traveller asked to change their account's email address and the new address never took effect (ADR-0039). Nothing is lost: the account keeps its old address until the link sent to the new one is used.
**Confirm:** `skyhub_account_email_changes_total` by `outcome`: `requested` (link issued), `confirmed`, `invalid_link` (a wrong, used, expired or someone else's link, or the address was taken meanwhile), `taken` (the new address already has an account: nothing was sent, by design, and nothing tells the traveller), `throttled` (asked again within two minutes), `password_refused`. If `requested` is high and `confirmed` is near zero, the emails may not be arriving: see NotificationDeadLettered, and Mailpit locally (`account.email-change-link` events). Loki `{service="booking"} |= "Address change"` shows `account.id` for each step; it never shows an address.
**Mitigate:** the usual causes are an expired link (one hour; ask again, no sooner than two minutes after the last request), opening the link in a browser that is not signed in to the account (sign in, then open the link again), or an address that already has an account (use another, or sign in to that account instead). A burst of `password_refused` is someone guessing the password through this form: it counts toward the same lock as sign-in (five wrong tries, 15 minutes) but is not in the failed-sign-in alert, so watch this counter. The old address is emailed a notice when the account moves (ADR-0040): `skyhub_notifications_total{type="account.email-changed"}` should keep pace with `skyhub_account_email_changes_total{outcome="confirmed"}`; a gap means notices are parked (NotificationDeadLettered). Every open session on the account ends when it moves (ADR-0041): `skyhub_account_sessions_total{outcome="revoked"}` counts tokens refused for that reason, so a short burst of `revoked` right after a change (or after a `signed_out_everywhere`, which a person does from the account page, ADR-0047) is the old sessions being turned away, and a burst with no change behind it means something is replaying old tokens or an epoch was raised by hand. A booking token already opened from the account (ADR-0034) is a separate credential and stays valid for up to its own 15 minutes. If someone reports an account that moved without them, treat it as a security incident: the account now belongs to whoever controls the new address and knew the old password, and there is no self-service way back. `Account moved to a new address` in Loki gives the `account.id` and time; moving the address back is a database change by a developer.

## AccountLoginGuessing

**Means:** more than 30 traveller sign-ins failed or were refused for a locked account in 10 minutes (ADR-0033). Either someone is guessing passwords, or travellers are struggling with a broken sign-in. Five wrong passwords lock an account for 15 minutes, so a guesser is slowed to about 20 tries an hour per account. The threshold is higher than for staff because there are many more travellers.
**Confirm:** `skyhub_account_logins_total` by `outcome` (`failed`, `locked`, `success`). If `success` is also near zero, sign-in itself may be broken: check the booking service. Loki `{service="booking"} |= "Traveller sign-in"` shows `account.id` for locked accounts (a failure for an unknown address has no id). Many different accounts locking at once is guessing across accounts; one account repeating is usually a person.
**Mitigate:** if it is guessing, check the edge rate limit is on (ADR-0009) and block the source address there; the website already allows 10 sign-ins per client per 15 minutes. A locked account unlocks itself after 15 minutes, and its owner can also ask for a reset link (that clears the lock). Nothing was accessed unless `Traveller signed in` follows a run of failures for the same `account.id`; in that case ask the owner to reset the password, which also stops the old one working.

## A booking is missing from "Your bookings" (no alert)

**Means:** a traveller says a booking made with their address is not on their account page (ADR-0034). Almost always the booking was made with a different address (a typo, a `+tag`, dots: the address must match ignoring case only), or the traveller signed up with another address.
**Confirm:** ask which address the confirmation email went to. Loki `{service="booking"} |= "addresses could not be read"` is one line per start with how many bookings the backfill skipped (`backfill.unreadable`) and the first five ids (`pnr.ids`), and `skyhub_contact_index_unreadable_total` counts them: usually a changed `SKYHUB_PII_KEY`, which also breaks reading those bookings. `SELECT count(*) FROM pnr WHERE contact_email_index IS NULL;` should be 0 once the booking service has started after the upgrade. `skyhub_contact_index_backfilled_total` counts what the last start indexed.
**Mitigate:** a different address: use Manage booking with the reference and last name (the booking is not theirs to list). Unindexed rows: restart the booking service (the backfill runs at every start and skips nothing it can read); if the key was changed, restore the old key. `skyhub_my_bookings_total{outcome="denied"}` rising fast is someone trying references that are not theirs: the edge rate limit applies, and an account token already needs a confirmed password.

## ServiceDown

**Means:** Prometheus cannot scrape a service for 1 minute.
**Confirm:** `up` in Prometheus; the service's readiness endpoint on its management port (`:9081/actuator/health/readiness`, `:9082/...`).
**Mitigate:** restart the instance; check its last log lines in Loki for the startup failure (missing `SKYHUB_PII_KEY` stops booking from starting by design).
