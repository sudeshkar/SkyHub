# ADR-0086: Designed emails with the SkyHub look

**Status:** Accepted
**Date:** 2026-11-14

## Context

ADR-0050 added an HTML version beside every plain-text email, made from the text, and kept it deliberately plain: one column, paragraphs and line breaks. It said branding and a logo would come later. In the inbox the emails still look like a system notice and nothing like the website ("Clear Sky", ADR-0084), and they are about to appear in a demo video. The hand-wrapped lines of the text also show as breaks in the middle of sentences in HTML.

The rules of ADR-0050 stay: everything is escaped, only `http` and `https` addresses are links, and opening an email loads nothing from the internet.

## Options considered

1. **A template engine (Thymeleaf) with one template per email.** Rejected: twelve templates beside twelve plain texts, which can drift apart, and the composers would have to change.
2. **A structured email model that renders both the text and the HTML.** The cleanest end state, but it rewrites every composer and its tests to change only how emails look. Kept for when an email needs a shape the text cannot express (the e-ticket may be that).
3. **Keep deriving the HTML from the text, but read the shapes the text already has and lay them out, inside a branded frame.** Chosen: the composers and their thirty tests do not change, a new email gets the design with no extra code, and the text and HTML still cannot say different things.

## Decision

`HtmlBody.from(subject, text)` builds every HTML email:

- **One frame for all:** a navy header with the SkyHub logo and name, a blue-to-sky strip, the **subject as the heading**, a white card on a light page, and a footer naming the two hubs and saying SkyHub is a portfolio project, not a real airline. The first paragraph is also a hidden **preview line**, which inboxes show beside the subject. Colours are the website's tokens; fonts are system fonts.
- **The text's shapes become layout:**
  - a paragraph whose every line is `Label: value` (a short label of words, so a sentence with a colon stays a sentence) becomes a **table of facts** ("Booking reference ABC234");
  - **indented lines**, under an optional `Heading:` line, become a **card** with one row per line (flights, passengers, seats);
  - a paragraph that is **only a web address** becomes a **button** ("Open the link"), with the address under it to copy;
  - anything else is a paragraph that **flows**: the text's line breaks only wrap the plain text, so they become spaces.
- **The logo travels inside the message** as an inline picture (`cid:skyhub-logo`, the website's 9 KB icon) and is never fetched, so opening an email still tells nobody anything. Mail programs show it as part of the email, not as an attachment.
- **Built for mail programs:** tables and inline styles only, no style sheet, no script, no web font, `color-scheme: light`.
- The plain text is unchanged, and so is every composer.

## Consequences

- Positive: every email, present and future, looks like SkyHub, with no change to how emails are written.
- Positive: the no-tracking promise holds with a logo in it.
- Negative: the layout depends on the composers keeping their text conventions (indented lists, `Label: value`). A new email that breaks them still renders, as plain paragraphs. The tests name each convention.
- Negative: each email grows by the logo (about 12 KB once encoded).
- Negative: the confirmation still lists only flight numbers and dates, because that is all its event carries. Showing the route, times and seats, and attaching an e-ticket, is the next slice.
- Not done: dark-mode colours (mail programs that force dark mode may invert the header), and testing in Outlook or Gmail.

## Verification

- 16 HTML tests and 7 SMTP adapter tests (the logo is inline, a PNG, and the HTML points at it), and a Mailpit test over real SMTP (the designed HTML arrives, the logo is listed as an inline picture and there are no attachments).
- Seven kinds of email (confirmed, cancelled with a refund, rebooked, set-password link, check-in open, checked in with a pass attached, delayed) were sent to a throwaway Mailpit and looked at in Chrome. That found the breaks in the middle of sentences, which were fixed; Mailpit's own HTML check scored the confirmation 89%.
- `./mvnw clean verify` for the notification service: 191 tests, no failures.

Not verified: real inboxes (Gmail, Outlook, Apple Mail), dark mode, a screen reader.
