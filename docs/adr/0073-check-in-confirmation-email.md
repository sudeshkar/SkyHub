# ADR-0073: A confirmation email when someone checks in

**Status:** Accepted
**Date:** 2026-11-03

## Context

The ROADMAP listed "a boarding-pass attachment" under More notifications. Today a traveller who checks in gets nothing in their inbox: the boarding passes are on the page they were looking at, and there is no email at all at check-in (the only check-in email is the reminder that it has opened, ADR-0029). Sending the pass itself, as an image the traveller can open on a phone with no signal, needs two things that do not exist yet and each of which is its own decision: the signed pass on the Kafka topic, where it would be a bearer credential for boarding, and a way to draw a QR image and attach it on the server (ADR-0071 draws it in the browser, on purpose).

## Options considered

1. **Do the whole attachment in one slice.** Rejected as too large to review honestly: a sealed credential on the topic (ADR-0046's problem again), an image library in a Java service, MIME attachments in the sender, and a new event, in one change.
2. **Send the pass text in the confirmation, as the traveller sees it on the page.** Rejected: the signed pass is a credential for boarding, and an email address is a weaker place than the page behind the booking's own session; the topic would also hold a usable secret (ADR-0046 sealed the one-time links for exactly this reason).
3. **Split it. First the confirmation, which carries nothing secret; then the attachment, with its own security decision.** Chosen. The confirmation is useful alone: it tells the traveller that check-in worked, which seat and boarding number each person has, and until when they can change their mind.

## Decision

- **A new event, `booking.checked-in` (version 1),** written by the booking service in the same transaction as the check-in rows (so it exists exactly when the check-in does, and not for a lost race, a refusal or a repeat). It lists **only the passengers that check-in checked in** (a party member checking in later gets their own), with first name, last name, seat and boarding number, plus the flight, the expected departure (revised if the flight was delayed) and when check-in closes. The shared example `booking-checked-in.v1.example.json` is read by both services' tests, so the two cannot drift.
- **No boarding pass text, barcode or signature is in the event or the email.** Only what the traveller already knows. The pass stays behind the booking's own session.
- **The email** ("You are checked in for SkyHub flight SK501 on 2026-11-02") lists each passenger with seat and boarding number, gives the departure time, says the boarding pass with its QR code is in Manage booking (booking reference and any passenger's last name), and says they can check out, and then change their seat, until check-in closes. Its HTML form is derived as for every email (ADR-0050).
- **The consumer is the existing one:** it records each event id once, so a redelivery or a retry (ADR-0066) sends one email.
- **Efficiency:** no extra query. The service already has the booking, the flight, the new rows and the departure time; the event is built from them, one outbox row per check-in.
- No new metric, alert or runbook entry: the event and email count under the existing `skyhub_notifications_total{type="booking.checked-in"}` and the existing failure alerts (ADR-0066).

## Consequences

- Positive: a traveller who checks in gets a record of it in their inbox, with the numbers they will be asked for.
- Positive: nothing secret was added to the topic or to email, and the design for the attachment can be decided on its own.
- Negative: **the email does not carry the pass.** A traveller without signal at the airport still needs the page. The attachment slice addresses that, and has to settle how a pass travels on the topic.
- Negative: **one email for each check-in, not for each flight.** A party that checks in one by one gets one email each time (as the confirmation says only who was just checked in). Nothing joins them.
- Negative: **the "check-in open" email still says everyone checks in together**, which stopped being true when passengers could check in one at a time (ADR-0058). Noticed here, not changed here.
- Negative: **an email is sent even if the traveller checked in a second ago and can see the page.** There is no setting to turn it off.
- Negative: if the flight is delayed after check-in, the email's "check-in closes" time is the one at the moment of check-in; the delay email (ADR-0020) carries the new one.
- Not done: the QR pass as an attachment, an opt-out, and SMS.

## Verification

Unit tests for the check-in service (the event is written with only the newly checked-in passengers, their numbers and the closing time, uses the revised departure of a delayed flight, and is not written for a repeat, a lost race or a refusal), for the producer (the payload equals the shared example), for the event (the example is read, and each missing piece is refused), for the email (every passenger's seat and number, where the pass is, until when, and no pass text) and for the consumer (one email for a redelivered event, routed from the topic). An integration test on real PostgreSQL through the real filter chain: one confirmation queued for a check-in that checked someone in, none for a repeat, one more for the rest of the party, and none for a refused check-in; and an embedded-Kafka test that the example becomes exactly one recorded email. The full builds of both services pass: booking with 1006 tests and notification with 162. The email was **not** sent through a real mail server and the live check (`stack.py check`) was **not** run or extended.
