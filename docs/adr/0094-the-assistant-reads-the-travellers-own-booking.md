# ADR-0094: The assistant reads the traveller's own booking

**Status:** Accepted
**Date:** 2026-11-23

## Context

The traveller assistant answers from the help centre and flight search (ADR-0090 to ADR-0093). Travellers' commonest questions are about their own booking: "am I checked in?", "is my flight delayed?", "what is my seat?", "has my refund gone through?". ADR-0090 planned booking tools that act **only with the traveller's own manage token** (ADR-0011): the short-lived, one-booking token the Manage page gets with the booking reference and a passenger's last name.

What the booking service gives for a manage token (`api-specs/booking-api.yaml`):

- `GET /pnrs/{ref}`: status, flights (number and date), refund, **passengers' names**, each passenger's seats, **the contact email**;
- `GET /pnrs/{ref}/disruptions`: delays and cancellations on its flights;
- `GET /pnrs/{ref}/check-ins/{n}`: the boarding passes of a checked-in flight, each with the passenger's name and **a signed barcode**, which is as good as the pass.

The model is Groq's, a third party (ADR-0093). The disruption agent was built to see no personal data (ADR-0022); the same standard applies here.

## Options considered

Where the manage token comes from:

1. **The model asks the traveller for the reference and the last name, and passes them to a tool.** Rejected. The traveller's credentials would travel through the model and to Groq; a prompt injected into the conversation could make the model try other references, a new way to probe bookings that bypasses the website's rate limit on the Manage form (ADR-0009); and the model could pass someone else's reference.
2. **The website's server sends the token it already holds** (the httpOnly cookie of the Manage page, ADR-0011) with the message, and the assistant binds it to the booking tool for that one message. The model calls the tool with **no arguments** and never sees the token or the reference. Chosen.

What the model sees:

1. **The booking as the API returns it.** Rejected: names, the contact email and the barcodes would go to Groq, and a barcode is a usable boarding pass.
2. **Only what the questions need, with passengers by position.** Chosen.

## Decision

- **The request carries the booking out of band.** Two optional headers on `POST /api/v1/assistant/messages`: `X-Booking-Reference` (six capitals or digits, the booking API's pattern) and `X-Booking-Token` (the manage token). Both or neither; one alone, or a reference of the wrong shape, is a 400. The assistant never stores them; the token is left out of every `repr` and log line.
- **A fourth route, `booking`**, for questions about the traveller's own booking. With no booking on the request, the traveller gets a standard reply saying to open the booking on the Manage booking page and ask again there (outcome `needs_booking`); no crew runs.
- **A booking agent** (CrewAI), with two tools: `get_my_booking` and `search_help_centre` (for "can I still get a refund?" about that booking). The booking tool it is given is **bound to this message's reference and token**: its schema has no parameters, and the wrapper adds them to the call after the model has made it.
- **`get_my_booking` lives in the traveller MCP server** (ADR-0092), as its third tool, read-only, taking the reference and the token for each call and keeping neither. The server still holds no credentials of its own; it is now also told where the booking service is (`SKYHUB_BOOKING_API_URL`), and the API still passes it neither the model's key nor its own token.
- **What it returns**: the booking's status; for each flight its number, date, airports, UTC times (from flight-search's public departure lookup), status and new departure time if delayed, the seat of each passenger by position ("passenger 1: 12A"), and which passengers are checked in; the refund's status and amount for a cancelled booking; and a `note` that passengers are not named on purpose, times are UTC, and seats, check-in and cancelling are done on the Manage booking page. **Never** names, the contact email, the reference or a barcode: a test feeds the client a booking full of them and checks none come out.
- **Bounded work**: at most 4 flights a booking, so at most 1 + 1 + 4 + 4 calls (booking, disruptions, check-ins, departures), each with the 5-second timeout.
- **Failures are worded for the traveller and say nothing more**: a token the booking service refuses (`401`) is "the booking session has ended; open it again on the Manage booking page"; a `403` or `404` is the same "could not open this booking", so nothing is learnt about other bookings; a service that is down is "not answering, try again".
- **The code's check (ADR-0093) covers it**: flight numbers named in a booking answer must be ones `get_my_booking` or `search_flights` returned for this message; cited articles must be ones the help centre returned.
- **Still read-only.** There is no tool to cancel, change a seat or check in. The booking agent is told to point to the Manage booking page for those.

## Found while building it

- **A strict provider refuses a tool with no parameters.** The first live run sent the bound booking tool's schema as `{"required": [...]}` with no `properties`, and Groq refused every call with a 400. The scripted model of the tests cannot see this; only a real provider validates schemas. The bound tool now has one optional field, `about`, which is ignored, and a test holds that its schema has properties and no field that could name a booking.
- **That 400 was reported as "the model is unavailable".** Only the provider being out of reach, slow, rate-limited, failing (5xx) or refusing the key is now `ModelUnavailable` (the traveller's 503); any other refusal is a fault in this code and is raised as it is (a 500 the logs show).
- **The router sent a rules question to the booking crew** because it said "my booking" ("If I cancel my booking tomorrow morning, do I get my money back?"), and with no booking open the traveller was told to open one. The router is now told that a question about SkyHub's rules is `help` even when it mentions "my booking".
- **"Am I checked in?" was answered wrongly for a party.** With only passenger 1 checked in, the model said "you are already checked in", which would send passenger 2 to the gate without a pass. The booking task now says to name the passenger each fact is about whenever there is more than one, and never to say "you" for the whole party when they differ. The eval for it asks for "passenger 1".
- **"What seats do we have on the flight to New York?" went to the flights crew**, which answered about cabins in general. The router is now told that "I", "we", "my" or "our" about a flight the traveller is booked on is `booking`, and, when the website has a booking open, that one is open (only that: never its reference or token, which a test holds).
- **CrewAI retries a rate-limited call itself, 9 times over about 8 seconds**, before it gives up. A traveller hitting the free tier's limit waits that long for the 503.
- **CrewAI and the MCP adapter do not log tool arguments**: CrewAI's events are emitted for the tool the agent sees, the bound tool with no booking in it; `BaseTool.run` and `mcpadapt` log nothing about a call's arguments.

## Consequences

- Positive: "is my flight delayed?" is answered from the booking itself, without the traveller typing a reference into a chat, and without Groq learning who they are.
- Positive: the assistant opens no new way to reach a booking. It can only read the booking the website's server already holds a token for, and that token came through the Manage form and its rate limit.
- Negative: **the chat can only answer about a booking that is open on the Manage page.** A traveller who has not opened it is told how; the website chat (ADR-0090's slice 6) is where that is made smooth.
- Negative: **booking facts in an answer are not checked in code**, only flight numbers. "You are checked in" when the tool said otherwise is caught by the answer evals, not by code.
- Negative: the token reaches the MCP server process over its stdin, in the same machine. It is not stored or logged there, but a process dump could hold it for the 15 minutes it lives.
- Negative: seats and the refund amount do go to Groq; they say nothing about who the traveller is.
- Follow-up work: the website chat forwarding the Manage page's booking (slice 6); answering about the bookings of a signed-in account (an `account` token, ADR-0033), which this does not do.
