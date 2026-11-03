# ADR-0078: Saved passengers

**Status:** Accepted
**Date:** 2026-11-04

## Context

Everyone who books for a family or a group types the same names again. A traveller account exists (ADR-0033) and lists the bookings made with its address (ADR-0034), but it keeps nothing a booking could reuse. The ROADMAP listed "saved passengers" with a condition: *revisit ADR-0054's mailbox trust first.*

That condition matters. ADR-0054 lets anyone who can read an account's old mailbox take the account back for seven days, and weighed that as acceptable because of how little an account holds: "an email, no password they know, and the bookings listed under that address". Saved names make an account hold more, so the question is what exactly is kept, and whether the trust still holds.

## Options considered

1. **Keep the names in the browser (local storage).** Rejected: it is per device, lost with the browser, and puts names where any script on the page can read them; the project keeps tokens and personal data out of reach of page scripts.
2. **Save everything a booking asks for.** Rejected: the system holds **no date of birth, passport or contact detail** anywhere, so the thing to save is only a first and a last name. Anything richer would change the answer to the mailbox question and is out of scope.
3. **Save names on the account, encrypted, bounded, and visible only to that account.** Chosen.

## Decision

- **Names only, at most ten for each account,** each a first and a last name of 1 to 50 characters (the same limits as a booking). A new migration adds `saved_passenger`, deleted with its account, with both names **encrypted at rest** like every other personal datum (`PiiCipher`).
- **`GET`, `POST` and `DELETE /api/v1/accounts/me/passengers`** need an account token and nothing else: a booking token or a staff token is refused (403) and no token is a 401. **The account is the token's subject, never something the caller names.** A name's number belongs to its account: removing one that is not there, or is another account's, is the same 404, so the answer says nothing about anyone else's.
- **Saving a name that is already kept** (ignoring case and spaces) answers 200 with the one already there and uses no second place. The eleventh new name is a 409. The limit holds when requests arrive together: adding takes a lock on the account's own row, so twelve at once give exactly ten saved and two refused (a test does this).
- **No event, email, log line or metric label ever holds a name.** The count of saves, repeats, removals and refusals is `skyhub_saved_passengers_total{outcome}`.
- **The website** adds a "Saved passengers" section to the account page (add, remove, how many places are used, a plain statement that only names are kept) and a picker in the booking form: on the passengers step each passenger can **fill their first and last name from a saved name**, and the traveller can still type over it. Nothing about the form is required to use it.
- **The account cookie is scoped to `/account` (ADR-0033), so the booking page cannot read it.** This was found while building the picker. Widening the cookie to `/` would undo a security decision made on purpose, and a server action called from the booking page would also be sent to that page, without the cookie. So the names are served by a small route **under** `/account` (`GET /account/saved-passengers`), which the booking form asks for **only when the traveller reaches the passengers step**. It gives only first and last names (no ids, no address), is never cached, answers the same empty list to a visitor who is not signed in, whose session has ended or when the booking service is down, and is rate limited.
- **The mailbox question, answered.** A person who gets an account through its old mailbox (ADR-0054) can now see the names saved on it. We accept that, for these reasons: the names are the ones the account's owner has already typed into bookings that the same mailbox holder can already see by address; the account owner can take the account back and that throws the password away and ends every session (ADR-0054); and **nothing other than names is kept**. If a date of birth, a passport number or a phone number is ever added, this decision must be made again, because those are not what a mailbox holder should be given.

## Consequences

- Positive: a traveller who books for the same people does not retype them, and can see and remove what is kept.
- Positive: nothing new is exposed to the booking page's scripts that they could not already ask for as the signed-in traveller, and the account cookie keeps its narrow scope.
- Positive: the boundaries are the existing ones (account token, encrypted at rest, rate limit, deny by default), and a name in a URL, a log, an event or an email is not possible by construction.
- Negative: **whoever can sign in to the account, or reach it through the old mailbox for seven days, sees the saved names.** They are the names of the people the owner travels with, which is more than "an email and some booking references".
- Negative: **names on a shared computer.** Someone left signed in on a computer that is not theirs shows their saved names to the next person. "Sign out everywhere" (ADR-0047) ends that.
- Negative: **every visitor who reaches the passengers step makes one extra request** to `/account/saved-passengers`, which answers them with an empty list. It is small and not cached, and it does not hold up the form.
- Negative: **it needs the booking page's JavaScript.** Without it the form is as it always was.
- Negative: the picker is not told apart by anything but the name: two saved people with the same name are the same entry.
- Not done: a "save these names for next time" tick at the end of a booking, editing a saved name (remove and save again instead), and saving the contact email.

## Verification

Unit tests for the service (saved trimmed and listed oldest first; a repeat in any case answered with the one kept and no second place; a blank or over-long name refused before anything is looked at; the tenth place and the refusal of the eleventh without changing anything; one account's names never reaching another and removal of another's id being "not found"), for the controller with the real security rules (200, 201, 204; 404 and 409 with their titles; 400 for a missing or over-long name without calling the service; nobody, a booking token and a staff token refused; the account taken from the token; never cached) and for the website (the route, the client the booking page uses, both actions, the account section and the picker). An integration test on real PostgreSQL through the real filter chain: save, repeat, list, remove and remove again; the stored columns hold none of the name; another account sees and removes nothing; twelve simultaneous saves leave exactly ten; no token is a 401. The whole booking build ran clean with 1054 tests and the frontend with 555, `tsc`, `eslint` and `next build` clean. The picker was **not** tried in a real browser, and the route's cookie behaviour (that the browser sends the `/account` cookie to `/account/saved-passengers` from the booking page) was reasoned from the cookie's path and **not** observed.
