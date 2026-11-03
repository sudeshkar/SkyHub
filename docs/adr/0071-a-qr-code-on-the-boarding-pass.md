# ADR-0071: A QR code on the boarding pass

**Status:** Accepted
**Date:** 2026-11-03

## Context

A boarding pass (ADR-0013) carries a signed line of text that the gate reads: the traveller's page showed that line as plain characters. A person cannot hold a phone up to a scanner and have it read twelve dozen characters, so the pass could only be used by pasting or typing the text. ADR-0013, ADR-0055 and the ROADMAP all listed "a QR/PDF417 image of the pass" as not done, and it is the groundwork for a boarding pass in the email (ADR-0066's list) and for scanning with a phone camera at the gate.

## Options considered

1. **A code drawn by an image address** (`/qr?text=...`, or a third-party image service). Rejected on security grounds: it puts a signed boarding pass into a URL, and so into server and proxy logs, browser history and caches, and, for a third party, into someone else's. DEVELOPMENT.md keeps secrets out of addresses (section 4).
2. **Draw it on the server (a Java library) and send an image.** Rejected for this slice: the page already holds the text, and nothing has to cross the network to draw it. It becomes the right place when the code goes in an email, where the notification service has no browser (a later slice).
3. **Draw it in the page as an inline picture, from the text the page already has.** Chosen.

**PDF417 or QR.** Airlines print PDF417 (and Aztec) because gate hardware reads them from paper. A traveller holds a phone, and every phone camera and every common scanner reads QR. There is no small, dependency-free, maintained PDF417 encoder for the browser, and writing one is a correctness risk with no way to confirm it here. QR it is; PDF417 would be a second encoder behind the same component.

## Decision

- **`qrCode(text)`** (`src/lib/qr.ts`) is a pure function: it encodes the text at error-correction level M (15% damage tolerated) and returns the size and **one path** that draws every dark module, as one run per stretch in a row. The same text always gives the same drawing, with no network and no clock.
- **`QrCode`** (`src/components/manage/qr-code.tsx`) draws that path as an inline SVG: `role="img"` with a label a screen reader says, sharp at any size, **always black on white with the quiet zone inside the picture**, because a code drawn in a dark theme's colours often cannot be read by a scanner. The pass text is not in the markup; the card shows it once, under the code.
- **`BoardingPassCard`** shows the code and keeps the text under it, so it can still be copied (and so the gate screen, which takes text, works as before, ADR-0062).
- **The encoder is `uqr`** (MIT, no dependencies, from the unjs project), a runtime dependency of about 27 kilobytes. **`jsqr`** (Apache-2.0, no dependencies) is a **development** dependency only, used as a second, independent reader in the tests.
- **Cost:** linear in the number of modules, at most 177 by 177 for the largest code; a typical pass (about 94 characters) is a version 6 code, 41 by 41 modules, and a pass of 300 characters (the longest the gate accepts) is a version 10 code, 57 by 57. These were measured with the library. Drawn on the server when the page is built, so nothing runs in the browser.

## Consequences

- Positive: a traveller can show a phone to a scanner, and the code can be reused on the gate's camera and in an email.
- Positive: nothing about the pass leaves the page: no address, no log, no third party.
- Positive: the tests do not trust the encoder. They draw the path the way a browser would and point a **different** library's decoder at the picture, which reads back exactly the pass text, including the 300-character pass.
- Negative: **it was read by one decoder, not by a real scanner or a phone camera.** QR is a standard and the second reader is independent, but a gate's hardware was not tried. The only thing on this machine that has seen it is a test.
- Negative: **it is a QR, not the PDF417 an airline gate expects.** Existing airport hardware would need QR enabled; this project has no gate hardware.
- Negative: **a new dependency in the website's runtime** (`uqr`, version 0.1.x, pre-1.0), and one in its tests. Both have no dependencies of their own; the lockfile pins them.
- Negative: **a pass is long, so the code is denser than a short link's** (41 by 41 modules for a typical pass). On a small or dim screen it reads less easily; the card draws it at a size that suits a phone and lets the person enlarge the page.
- Not done: scanning with the camera at the gate, the code in the boarding-pass email, PDF417, and a "full brightness" display for the code.

## Verification

Unit tests for the encoder (a real pass and the 300-character maximum both decode to exactly their text with an independent reader, the four-module quiet zone, the same text giving the same drawing, runs not single boxes, and empty text refused), for the component (an inline `svg` with a label, no image, link or script, the pass text absent from the markup, always black on white and sharp) and for the pass card (the code beside the details, the text kept under it, a different code for a different pass). The full frontend suite (463 tests), `tsc`, `eslint` and `next build` pass. **No real scanner or phone camera was tried**, the page was **not** looked at in a browser, and no backend or live check applies (no service changed).
