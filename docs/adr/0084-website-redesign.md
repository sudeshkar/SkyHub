# ADR-0084: A website redesign for fewer steps and less scrolling

**Status:** Accepted
**Date:** 2026-11-10

## Context

The website worked, but it was hard to use. Walking through every journey by hand turned up the same few problems again and again:

- **Long pages.** The seat map was 29 rows stacked vertically (about 1,250 px), with the button to go on below it. The Review step and every staff tool were one narrow column that ran off the screen.
- **No guidance between steps.** On a trip with a connection, a traveller who had chosen a seat on the first flight was not told that the second flight needed one too; the Continue button just stayed grey.
- **A confirmation that disappeared.** The confirmation was a state of the booking page. A refresh, or Back then Forward, showed an empty seat map for flights already paid for, which looked like a failed booking and invited paying twice (the idempotency key only covers one attempt).
- **Dated look.** A warm "Desert Dusk" palette with a serif display face, small square buttons, and a stack of sections on the manage and operations pages.

## Options considered

1. **Patch each page.** Rejected: most problems share a cause (one-column stacks, no shared card or button style), so the same fix would be repeated in many places.
2. **A component library** (for example shadcn/ui). Rejected for now: it would change every page's markup and add a dependency for what a few shared classes and two small components do.
3. **One design pass with a few shared pieces.** Chosen: colour tokens named by role, a `btn-primary` and a `card` style, a `tool-form` layout, a `Modal` component, and page-by-page changes on top.

## Decision

**Look ("Clear Sky", `docs/design/06-clear-sky.md`).** A cool, saturated blue palette adapted from a reference app landing page, tuned so text meets WCAG AA (the accent is a shade deeper than the reference's blue, 4.8:1 on white). Tokens are renamed by role (`accent`, `line`, `wash`, `canvas`, `sky`) so a future palette changes values in one file. Inter for text and headings, IBM Plex Mono kept for codes. Pill-shaped gradient buttons; a frosted, sticky site header; a gradient footer. A new hero photo (no text inside it) with the brand line laid over it and animated in CSS: a library such as GSAP would add about 25 KB to the home page and delay the text until scripts load, for an effect CSS does in a few lines; with reduced motion the words simply appear.

**Booking.**

- The seat map is drawn **sideways inside an aeroplane outline** (nose on the left, rows as columns), so a whole cabin fits in one screen height. It pages with arrows (and swipe or wheel), jumps to a cabin, explains its colours in a key, and hides the scroll bar. The outline is one SVG drawn to the measured size of the seats.
- **Choosing a seat leads on**: to the next passenger without one, then to the next flight that needs seats, saying so; the flight tabs show a tick when done; the main button reads "Next flight: SK…" until every flight has seats.
- Each step's **Back and Next sit beside the heading in a sticky bar**; the seats step's Back returns to the flight results.
- The **Review step is two columns** (the trip, the payment) so it and the Pay button fit on one screen.
- The **confirmation is its own page, `/book/confirmed`**, reached with `router.replace`: Back goes to the results, and a refresh or Forward shows the confirmation again. What it shows is kept in **this tab's session storage** only (the reference, the email as the service already masks it, the amount, names and seats; never payment details), cleared when the tab closes, and **never in the address**, which ends up in history and shared links. Without it (another tab, storage refused) the page points to Manage booking. Opening the booking page for flights booked a moment ago in the tab shows a pop-up warning with "See that booking" and "Book again"; it warns, it does not block, as booking the same flights twice can be meant.
- Blocking the browser's Back button was considered and rejected: browsers do not reliably allow it, it breaks a basic expectation, and it does not fix the refresh case.

**Manage booking.** The reference in a card; a card per flight, side by side, with a check-in status badge; boarding passes as a list of rows, each opening the full pass with its QR code in a pop-up; a ✕ to close the booking with Cancel booking beside it, and the cancellation confirmed in a pop-up.

**Operations.** A dashboard of tool cards in two groups (Disruptions, Boarding). A card opens that tool alone at `/ops?tool=<id>`, so Back returns to the dashboard and a tool can be bookmarked; a slim bar replaces the header there, and forms lay short fields two to a row so each tool fits on one screen. "Who has boarded" shows its three counts as tiles with a progress bar. Gate agents still see only the gate (ADR-0060).

**Pop-ups.** One `Modal` component: it takes focus and gives it back, keeps Tab inside, closes with ✕, Escape or a click outside, stops the page scrolling, and rises from the bottom on phones.

## Consequences

- Positive: each step of booking, the review and every staff tool fit on one laptop screen (checked at 1536 x 639 and 1536 x 695); the next action is always visible and named.
- Positive: the confirmation survives refresh, Back and Forward, and a repeat booking of the same flights is caught before payment.
- Positive: colours are named by role, so a new palette is a change to `globals.css` only.
- Negative: the seat map needs horizontal paging for long aircraft; a cabin plan with hundreds of rows would want a minimap.
- Negative: the confirmation lives only in the tab that booked. Another device or tab sees "find it in Manage booking". That is deliberate (no reference in the address), but less convenient.
- Negative: the operations dashboard shows no totals across flights, because no endpoint returns them. A stats strip ("today: delays, boarded") needs a new read endpoint in the booking service and its own ADR.
- Negative: the visual changes themselves are not covered by automated tests; behaviour is (arrows, cabin jumps, the seat key, leading on to the next flight, the confirmation page and its fallback, the repeat warning, the pop-ups, Back). Pages were checked by hand in Chrome.
- The change touched most website files at once (the palette rename alone), so it is one pull request with one commit per part rather than several slices; splitting it would have meant pull requests that conflict with each other.
