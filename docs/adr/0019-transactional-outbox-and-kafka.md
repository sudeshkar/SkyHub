# ADR-0019: Notifications through a transactional outbox and Kafka

**Status:** Accepted
**Date:** 2026-10-30

## Context

ADR-0007 decided that things a traveller does not wait for (email, later SMS and miles) are driven by events, not by calling other services from inside a booking. Today nothing tells a traveller anything: they must remember their reference. The first message to send is "your booking is confirmed".

The hard part is not sending an email. It is making "the booking was saved" and "the event was published" agree. Two separate writes can disagree: if the booking commits and the publish fails, nobody is told; if the event is published and the booking then rolls back, a traveller is told about a booking that does not exist.

## Options considered

1. **Publish to Kafka inside the booking use case** — simple, and wrong in exactly the two ways above.
2. **Call the notification service over REST after the booking** — a slow or broken email service now fails or delays bookings, which ADR-0007 set out to prevent.
3. **Transactional outbox** — the event is written to a table in the *same database transaction* as the booking, so both happen or neither does. A relay reads the table and publishes to Kafka afterwards.
4. **Change-data-capture (Debezium) on the outbox table** — the same idea without a polling relay, but one more system to run. Worth it at high volume.

## Decision

Option 3, with a small polling relay, and a separate notification service as the consumer.

- **Outbox.** Table `outbox_event` in the booking database. Confirming a booking writes its `booking.confirmed` event in the same transaction. A booking that fails (seat taken, payment declined, any rollback) leaves no event.
- **Relay.** Every few seconds it takes a batch of unpublished rows (`FOR UPDATE SKIP LOCKED`, so several booking instances never send the same row twice at once), sends each to Kafka **and waits for the broker to acknowledge it**, then marks it published. If Kafka is down, rows stay pending and are retried; nothing is lost and booking is unaffected. A crash between "sent" and "marked" re-sends the event, which is why consumers must be idempotent.
- **At least once, in order per booking.** The Kafka key is the booking reference, so events for one booking stay in order.
- **Notification service** (`services/notification`, its own database, ADR-0003). It consumes `booking.confirmed`, records the event id in a table with a unique constraint, and sends the email in the same transaction. A redelivered event finds its id already recorded and is skipped, so the traveller gets one email. If sending fails, the transaction rolls back and the message is retried; after a few attempts it goes to a dead-letter topic and an alert fires.
- **Email is behind a port.** The first adapter records the email and logs it, so the whole path runs and is testable without a mail server. A real SMTP adapter replaces it without changing the rules.
- **Personal data.** The event has to carry the traveller's email, because the notification service owns no bookings. The outbox row is encrypted at rest with the same key as the booking's own email (ADR-0010); the relay decrypts it just before publishing. The broker, though, holds the plain address for as long as the topic retains it (seven days here). Mitigations: the broker is not exposed outside the private network, the retention is short, and nothing else reads the topic. The alternative, the notification service asking booking for the address, needs a service-to-service credential and a synchronous dependency; it is the follow-up if the exposure is judged too high.
- **A shared example, not a schema registry.** `api-specs/events/booking-confirmed.v1.example.json` is read by tests in both services, so the producer and the consumer cannot drift apart unnoticed. New fields may be added; consumers ignore fields they do not know. Breaking changes mean a new `v2` event type.

## Consequences

- Positive: a failing mail server, a failing broker or a failing notification service never fails or slows a booking, and no confirmed booking is silently left un-notified.
- Positive: new consumers (SMS, miles, an audit feed) subscribe to the same topic without touching booking.
- Negative: a broker and a third service to run, and the traveller's email is on the broker for its retention period.
- Negative: eventual consistency. The email arrives seconds after the booking, and if the broker is down for an hour it arrives an hour late.
- Negative: polling adds a small constant load, and the table grows until published rows are purged (a daily purge of rows older than seven days is part of the relay).
- Follow-up: events for check-in opening; a real SMTP adapter; SMS; a replay tool for the dead-letter topic. (Cancellation and disruption events: ADR-0020.)
