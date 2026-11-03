# ADR-0070: Telling a moved traveller to check in again

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0069 lets staff move a booking that is checked in, and the move checks the party out of the old flight. Its first listed negative was that the traveller is not told: the rebooking email names the new flight and the seats, and says nothing about the boarding passes they already hold. A traveller who checked in, got passes, and then receives "your booking has moved" has no reason to think their passes are now dead. They would find out at the gate. The check-in-open reminder (ADR-0029) brings them back, but only when check-in opens for the new flight, which can be a day or more after the email.

## Options considered

1. **Always add a check-in paragraph to the rebooking email.** Rejected: most moved travellers were never checked in, and telling them to "check in again" about a step they did not take is confusing.
2. **Have the notification service work out whether the traveller had checked in.** Rejected: it does not hold check-in data and must not (ADR-0019 keeps services apart); it would need a call to the booking service on every email.
3. **Say it in the event, from the service that knows.** The booking service checks the party out in the move, so it knows exactly whether anyone was; it writes that into the `booking.rebooked` event, and the email adds the paragraph only then. Chosen.

## Decision

- **A new field on `booking.rebooked`: `checkedOut`**, true when the move checked anyone out of the flight they left (ADR-0069), false otherwise. It is an addition to version 1 and not a new version: an older consumer ignores a field it does not know (ADR-0019), and the notification service reads a message without the field as false, so the two services can be deployed in either order and messages already on a topic or waiting on a retry topic (ADR-0066) are read as they always were.
- **The shared examples carry it, so the two services cannot drift.** `booking-rebooked.v1.example.json` has `"checkedOut": false`, and a new `booking-rebooked-checked-out.v1.example.json` has `true`. The producer's tests require its payload to equal each example, and the consumer's tests read each example.
- **`RebookingService` checks the party out first and then writes the event**, in the same transaction, so the flag is what happened and the email exists exactly when the move does. `BookingEvents.bookingRebooked` takes the flag.
- **The email** for a checked-out traveller adds, after the seats: "You had checked in for SK501 on 2026-11-02. Your old boarding passes no longer work, and you are not checked in for the new flight. Please check in again on SkyHub when check-in opens; we will email you when it does." The HTML form is derived from it as for every email (ADR-0050). An email for a traveller who was not checked in is unchanged.
- **Reading a missing field as false is done in the event, not in the parser.** The shared parser refuses a missing primitive on purpose (it protects every other event), so the field is a nullable `Boolean` that the record's constructor turns into a plain true or false.
- No new metric, alert or runbook entry: the count of passengers checked out (ADR-0069) already exists, and the runbook entry for a refused move is updated to say the traveller is now told.

## Consequences

- Positive: a checked-in traveller who is moved is told what happened to their passes, and what to do, in the one email they will read.
- Positive: nothing changes for anyone else, and old and new services and messages work together.
- Negative: **the email says "check in when check-in opens", not when.** The time depends on the new flight and is not in the event; the traveller is emailed again when it opens (ADR-0029).
- Negative: **it depends on that later email being sent.** If the check-in-open reminder is switched off or fails for that booking, the traveller has only this email's promise.
- Negative: **a booking checked in on one flight and moved on another leg says nothing about the other leg.** Only the flight that was moved is named, which is the one whose passes are dead.
- Negative: the flag says that someone was checked out, not how many; a party of five where one had checked in gets the same sentence as one where all had.
- Not done: putting the time check-in opens for the new flight into the email, and a text message.

## Verification

Unit tests for the email (the paragraph when checked out, naming the old flight and saying the passes no longer work; nothing about checking in when not), for the event (both shared examples read, and an older event with no such field read as false), for the producer (the payload equals each shared example) and for the service (the flag is true when the move checked someone out and false otherwise, and no event is written for a refused or repeated move). The full builds of both services pass: booking with 998 tests and notification with 154. The email was **not** sent through a real mail server and the live check was **not** run.
