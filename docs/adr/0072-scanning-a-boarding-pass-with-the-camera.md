# ADR-0072: Scanning a boarding pass with the phone's camera

**Status:** Accepted
**Date:** 2026-11-03

## Context

The gate screen (ADR-0062) took a pass as text: a hardware scanner types it like a keyboard, or a person pastes it. A pass now carries a QR code (ADR-0071), and a gate agent with a phone has a camera. Without it the code could be shown but not read by anything the project ships, and the ROADMAP listed "the gate's phone-camera scan" as the one thing the browser tests had not clicked through.

## Options considered

1. **The browser's `BarcodeDetector`.** Rejected as the only route: it exists in Chrome on Android and some desktops, but not in Safari or Firefox, and the gate's phones are whatever people carry.
2. **A scanning library that runs its own camera UI** (a component that owns the video, the decoding and the permissions). Rejected: it hides the camera's lifetime, which is the thing that must be right (a camera left on is a privacy fault), and it is a large dependency for a small job.
3. **Our own small loop: open the back camera, draw a frame to a canvas a few times a second, read it with `jsqr`, hand on the text, stop the camera.** Chosen. `jsqr` was already in the project as the second reader in ADR-0071's tests; it has no dependencies and works in every browser.

## Decision

- **A "Scan with camera" button** on the gate panel opens the scanner. It opens the **back camera, without sound**, shows the live picture, and looks at a frame about eight times a second.
- **It reads one pass and stops the camera.** The first code read is handed on, the camera's tracks are stopped, and nothing looks for another: nothing is watching between passengers. Closing the scanner, or leaving the page, stops the camera too, including a camera that was still being opened when the page went away.
- **A read only fills the pass field.** It never checks or boards anyone: the person presses *Check pass* or *Board passenger* as with a typed pass (ADR-0062), so a stray read can never board a passenger. The field is now a controlled input and is **emptied once the pass has been sent**, so the next passenger starts clean.
- **The pictures stay in the browser.** A frame is read in memory and dropped; nothing is stored, logged or sent (a test asserts the scanner never calls `fetch`). Frames are shrunk to at most 640 pixels across, so the work is small on a phone.
- **Reasons the camera cannot be used are said plainly**: refused (allow it in the browser's settings), the browser or connection cannot offer one (a secure address, https or localhost, is needed), or no camera could be opened.
- **A pass-shaped answer only.** Text longer than the longest pass the gate accepts (300 characters) is not handed on.
- **Pure parts, one place each:** `readCode` (a frame to text), `openBackCamera` (permission and failure kinds), `grabFrame` (a picture from the video), and the scanner component, whose camera, picture-taking and timer are replaceable, so no test needs a camera or waits on a clock.
- **`jsqr` becomes a runtime dependency** (it was a development one in ADR-0071). Apache-2.0, no dependencies, pinned by the lockfile.
- No metric, alert or runbook entry: it is a way to fill a field, and what is done with the pass is counted as before (`skyhub_boarding_pass_*`).

## Consequences

- Positive: a gate agent with a phone can read a pass from a traveller's screen or a printout and check or board them, with no hardware scanner.
- Positive: the camera cannot be left running by accident, and a read cannot board anyone.
- Positive: the QR code of ADR-0071 can now be used from end to end in the product.
- Negative: **it was tried with scripted pictures, not with a real camera.** The tests draw the real code, hand it to the scanner as a camera frame and read it back, but no phone, lens, glare or screen brightness was involved. How well it reads a code on a dim phone in a queue is unknown.
- Negative: **it needs a secure address.** Browsers offer a camera only on https or localhost, so a gate that opens the operations page over plain http gets the "secure address needed" message.
- Negative: **one pass per press.** Reading the next passenger means pressing the button again. Continuous scanning would be faster at a busy gate and would need its own safeguards (no double boarding on a held code, a visible "last read").
- Negative: **a code that is partly off the picture or blurred is simply not read**; the person moves the phone. There is no focus control or torch.
- Negative: the page sets no `Permissions-Policy` or content security policy at all (checked); if one is added later it must allow the camera for this page.
- Not done: continuous scanning, a torch button, choosing between cameras, and a browser test with a fake camera.

## Verification

Unit tests for the reader (a real pass read from a picture of its code; a small code in a large empty scene, as a hand-held phone sees it; an empty scene; a code over 300 characters refused), for opening the camera (the back camera with no sound; no camera API offered; refusal; any other failure), and for taking a frame (nothing until the camera has a picture, shrunk to 640 across keeping its shape, small pictures left alone, no drawing surface). Component tests for the scanner (it opens the camera; keeps looking until a code is in view; hands on the text and stops the camera; does not look again; sends nothing; stops on close and on leaving the page; stops a camera that opened late; says why for each failure) and for the gate panel (opened only on request; a read fills the field and closes the camera and boards nobody; check and board work with what was read; the field is emptied after sending). They failed before the code. The full frontend suite (489 tests), `tsc`, `eslint` and `next build` pass. **No real camera was used**, the page was **not** looked at in a browser, and no live check applies (no service changed).
