# ADR-0074: The boarding passes attached to the check-in email

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0073 sent a confirmation when someone checks in, and deliberately left the pass out: the signed boarding pass is a credential for boarding, and putting one on the Kafka topic in the clear would make the topic a way to board. It named the attachment as the next step. A traveller at an airport with no signal can open an email they already have, but not the page behind their booking. ADR-0071 draws the QR code in the browser, so there was no picture a server could attach.

## Options considered

1. **Put the pass in the event in the clear.** Rejected: the topic, its retry topics and the dead-letter topic (ADR-0066) would each hold a usable boarding credential for anyone who can read them.
2. **Have the notification service ask the booking service for the pass when it builds the email.** Rejected: it adds a synchronous dependency from the consumer to the booking service for every check-in, in a service that today needs nothing from it (ADR-0019), and a call that fails would fail the email.
3. **Seal the pass in the event, as the one-time link tokens already are (ADR-0046), and have the notification service open it, draw the picture and attach it.** Chosen. The mechanism, the key and the discipline are the ones the project already has.
4. **Draw the QR picture in the booking service and put the image in the event.** Rejected: an image is a bigger, harder-to-seal payload on the topic, and the signed text sealed alone is smaller and reusable.

## Decision

- **The `booking.checked-in` event gains `sealedPass` for each passenger** (an addition to version 1): the signed pass, sealed with the existing AES-GCM sealer, **bound to the event type and to the traveller's address**, so a seal copied into an event for another address does not open. The pass text is never in the event in the clear; a test decrypts the stored payload as the relay would publish it and shows it does not contain the pass. It uses the key the two services already share (`SKYHUB_LINK_TOKEN_KEY`); the binding to the event type keeps one use of the key from standing in for another.
- **The notification service opens each pass, draws a QR code of it and attaches the picture.** A new port `PassImages` (the application owns it) and an adapter `ZxingPassImages` draw a one-bit PNG: level M error correction, a four-module quiet zone, eight pixels to a module, a few kilobytes for each. **ZXing core** (Apache-2.0, no dependencies) is the library.
- **`EmailMessage` can carry attachments**, and `SmtpEmailSender` attaches them. File names are `boarding-pass-SK501-11D.png`: flight and seat, **no names**. The log sender records how many, never what. The message's `toString()` shows a count, not the pictures or their names.
- **The email says the passes are attached** (and that they are also in Manage booking) only when they are. It never claims an attachment it does not carry.
- **All or none, and the confirmation always goes.** The pass is an addition to a confirmation, and the pass is on SkyHub either way. If any pass will not open (nearly always a key that differs between the two services), or a picture cannot be drawn, the email goes **without any attachments**, the log says so (event id, locator and the kind of failure, never the pass, the picture or the address), and `skyhub_notification_attachments_total{outcome="dropped"}` counts it. `attached` counts passes sent.
- **A new ticket alert, `NotificationAttachmentsDropped`,** fires when any email went without its passes in 15 minutes, because dropping is silent to the traveller and means the two services' keys have drifted. Runbook entry included.
- **An older event** (from before this slice, with no `sealedPass`) is read as before and gets the plain confirmation, with nothing counted.
- **Efficiency:** one open and one picture for each newly checked-in passenger, at most a party's worth for each event; the picture is small and drawn in memory and never stored.

## Consequences

- Positive: a traveller can open their boarding pass from an email they already have, with no signal, and the same code scans at the gate (ADR-0072).
- Positive: the pass travels the topic, its retry topics and the dead-letter topic only sealed, by the project's own existing mechanism, and the confirmation does not depend on it.
- Negative: **an email holds a boarding credential.** Anyone with the mailbox can board as that passenger until check-in closes or the pass is replaced or the passenger checks out (ADR-0055, ADR-0056 make an old pass refuse at the gate). That is how airlines work, but it is weaker than the page behind the booking's own session, and it is why the confirmation (ADR-0073) did not carry it.
- Negative: **the sealed pass is bound to the address and the event type, not to the booking.** A person who can write to the topic can copy a sealed pass into another check-in event for the same address; they gain nothing they could not already do, and cannot move it to another address. Binding to the booking reference as well would need a change to the sealer's interface.
- Negative: **one key does two jobs.** The shared key now also opens passes. A leaked key reveals passes still on a retained topic (the topic's retention window) and links; the event-type binding stops confusion between them, not a leak.
- Negative: **a key mismatch is easy to miss.** Emails keep going out and only the attachments disappear; the alert and the counter are what notice it.
- Negative: **the port and adapter are still named for link tokens** (`LinkTokenSealer`, `LinkTokenOpener`). They now seal passes too; renaming them is a mechanical change left out of this slice.
- Negative: **it was decoded by ZXing's own reader and the website's `jsqr` read the browser's code, not this picture.** No phone camera or gate hardware has read an attached image.
- Negative: **images may be blocked or not shown inline by a mail client**; they are attachments, so a person opens them.
- Not done: SMS, an inline picture in the HTML body, an opt-out, and a PDF.

## Verification

Unit tests for the producer (the event carries each pass sealed; the seals open, for that type and address, to the same text as the shared example's; everything else equals the example; the decrypted payload, as the relay publishes it, holds no pass text), for the check-in service (the passes in the event are the signed ones the traveller is given), for the opener (the example's sealed passes open to the pass text for the right address and not another), for the picture (a PNG that a reader decodes to exactly the pass, including the 300-character maximum; large enough to read and small enough to attach; a quiet zone; deterministic; empty refused), for the sender (each attachment with its name, type and bytes beside the text and HTML; none when there are none) and for the service (each pass attached and counted; the email still sent without any when a pass will not open or a picture will not draw, with the drop counted; a plain email for an older event). An embedded-Kafka and PostgreSQL integration test sends the shared example through the real opener and picture code and decodes each attached PNG back to the signed pass. The full builds of both services pass: booking with 1007 tests and notification with 180. `promtool check rules` passes. The email was **not** sent through a real mail server, no mail client opened the attachment, and the live check was **not** run.
