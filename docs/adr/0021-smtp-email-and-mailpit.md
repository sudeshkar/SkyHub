# ADR-0021: Send email over SMTP, with Mailpit as the local mail server

**Status:** Accepted
**Date:** 2026-10-30

## Context

The notification service (ADR-0019, 0020) wrote each email to its log. That proved the path, but nobody could see an email as a traveller would, and the one adapter that matters, the one that talks to a mail server, did not exist, so its failure modes were untested.

## Options considered

1. **A provider's HTTP API (SendGrid, SES, Mailgun)** — what many production systems use, but it needs an account and keys, sends real mail, and ties the code to one provider.
2. **SMTP** — a standard every provider also offers, so the same adapter works with a local catcher and a real relay.
3. **SMTP to a local catcher (Mailpit)** — a mail server that accepts everything and shows it in a web page, delivering nothing. No account, no keys, nothing leaves the machine.

## Decision

SMTP through Spring's `JavaMailSender`, with Mailpit in `docker compose` as the local server (options 2 and 3).

- **One adapter, many servers.** `SmtpEmailSender` implements the same `EmailSender` port the rules already use. Pointing `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME` and `SMTP_PASSWORD` at a real relay is the whole change for production.
- **SMTP is the default; the log adapter stays for running without any mail server** (`SKYHUB_EMAIL_MODE=log`). Tests of the consumer use the log adapter; the SMTP adapter has its own tests, against a real Mailpit container.
- **A refused or unreachable server is "unavailable", not "sent".** The adapter throws, the transaction rolls back (so the event is not recorded as handled), and the message is retried, then parked on the dead-letter topic. The failure text never contains the address or the body.
- **Short timeouts** (3 s to connect, 5 s to read and write), so a hung mail server fails fast instead of holding the consumer.
- **A mail outage does not make the service look down.** Spring's mail health check is switched off, so `/actuator/health` stays up while Mailpit or the relay is away. Restarting the service could not help, and messages are already retried and parked. Readiness reflects whether the service can work, not whether one dependency is having a bad hour.
- **UTF-8 throughout,** tested with accented and Sinhala names.
- **The sender address is a setting** (`SKYHUB_EMAIL_FROM`).

## Consequences

- Positive: emails can be opened in a browser at `http://localhost:8025`, and the end-to-end browser test can check that the confirmation arrives after booking and the cancellation email after cancelling.
- Positive: the failure path is proven on the real stack, not assumed: with the mail server stopped, a booking succeeded, the message was retried four times, parked on the dead-letter topic, and replayed by hand once the server returned. Replaying the same message again sent nothing (the consumer is idempotent).
- Negative: **retries are short** (four tries about two seconds apart). A mail outage longer than about six seconds ends in the dead-letter topic and needs a manual replay (the runbook says how). Longer, exponential back-off would hide short outages better, but a blocking retry also holds up every message behind it; non-blocking retry topics are the proper answer and are not built.
- Negative: **with a real SMTP server an email can, rarely, be sent twice.** If the server accepts a message and the service crashes before committing its record, the retry sends it again. The log adapter cannot show this. Fixing it needs the provider to deduplicate (a message id header) or a send-then-record outbox of its own.
- Negative: plain-text only. No HTML, no branding, no attachments (a boarding pass would be one).
- Follow-up: exponential back-off or retry topics; HTML email; a provider-specific adapter if a provider's features are wanted; a dead-letter replay tool.
