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
**Mitigate:** refund that charge by hand in the payment provider's dashboard using the `payment.charge` reference, then check the provider is reachable (see PaymentProviderUnavailable). There is no booking to cancel: no `payment` row exists for it. Automated reconciliation of charges without a booking is the planned follow-up (ADR-0015).

## CancellationRefundFailed

**Means:** a traveller cancelled a paid booking that qualifies for a refund (ADR-0016), the booking was cancelled, but the payment provider did not confirm the refund. The traveller is owed money and sees the refund as *pending*. Unlike RefundFailed, nothing is wrongly booked: the seats are free and the booking is cancelled.
**Confirm:** Loki `{service="booking"} |= "Refund after cancellation failed"` (carries `pnr.locator` and `payment.charge`). Overview → *Payments per minute by outcome* (`cancel_refund_failed`). Payments still owed: `SELECT locator, charge_id, amount, currency FROM payment WHERE status = 'REFUND_DUE';` in the booking database.
**Mitigate:** check the provider is reachable (see PaymentProviderUnavailable). Cancelling the booking again retries the refund (it is safe to repeat), so ask the traveller to press *Cancel* again from the manage page, or refund the `charge_id` by hand in the provider's dashboard and then set the row to `REFUNDED`. There is no automatic retry yet (ADR-0016 follow-up).

## StaffLoginGuessing

**Means:** more than 10 staff logins failed or were refused for a locked account in 10 minutes (ADR-0017). Either someone is guessing passwords, or a real colleague has forgotten theirs. Five wrong passwords lock an account for 15 minutes, so a guesser is slowed to about 20 tries an hour per account.
**Confirm:** Loki `{service="booking"} |= "Staff login"` (each line carries `staff.username`). Many different usernames from a burst is guessing; one username repeating is usually a person. Overview metrics: `skyhub_staff_logins_total` by `outcome` (`failed`, `locked`).
**Mitigate:** if it is a colleague, wait 15 minutes or ask an administrator to create them a new account (there is no password reset yet). If it is guessing, check the edge rate limit is on (ADR-0009) and block the source address there. A locked account unlocks itself; nothing was accessed unless a `Staff logged in` line follows a run of failures for the same name, in which case treat that account as compromised and remove it from the database (`DELETE FROM staff_account WHERE username = '...'`); its token still works for up to 30 minutes.

## PaymentProviderUnavailable

**Means:** more than half of payment attempts in the last 10 minutes could not reach the payment provider. Nobody is charged and nobody is booked while this lasts: travellers see "payment provider unavailable" and can retry.
**Confirm:** Overview → *Payments per minute by outcome* (`unavailable` far above `captured`); the provider's status page; `Payment provider unavailable` warnings in Loki.
**Mitigate:** nothing in SkyHub to fix if the provider is down; wait for it and tell support. If only SkyHub cannot reach it, check outbound network access and the provider credentials. A retry with the same Idempotency-Key is safe: the provider never charges one key twice.

## ServiceDown

**Means:** Prometheus cannot scrape a service for 1 minute.
**Confirm:** `up` in Prometheus; the service's readiness endpoint on its management port (`:9081/actuator/health/readiness`, `:9082/...`).
**Mitigate:** restart the instance; check its last log lines in Loki for the startup failure (missing `SKYHUB_PII_KEY` stops booking from starting by design).
