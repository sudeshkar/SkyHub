# ADR-0067: Filtering proposals by what happened to them

**Status:** Accepted
**Date:** 2026-11-03

## Context

After a disruption staff approve moves in batches (ADR-0035), and the operations page groups the proposals by destination and lets them search by reference (ADR-0043). Once a first batch has run, a flight of several hundred bookings is a mix: some moved, some already there, some refused by a rule (for example the connection rule, ADR-0036), some that failed because the service did not answer, and some not yet tried. The page showed them all in one list, so a person who wanted to see "what was refused, and why" or "try the failed ones again" had to read every row. ADR-0043 named this as its follow-up.

## Options considered

1. **A filter on the server.** Rejected: nothing about the outcome lives on the server; the results are the answers to the batches this page has just made, kept in the page. It would also be a contract change for a view.
2. **Hide moved bookings as they complete.** Rejected: it removes the record of what was done in front of the person who approved it, and does nothing for the refused ones.
3. **A "Show" choice with the outcomes and a count for each, applied in the browser, combined with the existing search, and acting on what is shown.** Chosen.

## Decision

- **A "Show" choice** above the list: *All*, *Not tried yet*, *Moved*, *Refused or failed*, each with how many bookings are in it. The outcomes do not overlap: *Moved* includes the bookings that were already on the flight; *Not tried yet* is a booking that has no result; *Refused or failed* is one that came back refused or failed. The bookings that need a person are shown under *All* only, because there is no move to try for them.
- **It combines with the search by reference**, and groups and passenger counts are made from the bookings that remain.
- **What is shown is what is acted on** (ADR-0043, unchanged): "Move N selected" counts and moves only the ticked bookings that are visible. Showing *Refused or failed* and pressing the button therefore tries just those again, and ticked bookings the filter hides stay ticked but are not moved; the page says how many ("hidden by the search or the filter").
- **Pure functions, one place to extend:** `groupProposals(proposals, query, outcome, results)` and `outcomeCounts` in `proposal-groups.ts`, with the list of choices in one table (`OUTCOME_OPTIONS`) and the meaning of an outcome in one function. A new outcome is a line in the table and a case in that function. The older two-argument call is unchanged.
- **Cost:** one pass over the list for the groups and one for the counts, `O(n + g log g)` as before, redone as the person types, picks an outcome or a batch completes. No server call. Nothing changed in the agent, the booking service or any contract.
- **No metric, alert or runbook entry:** it changes only what the page shows, and the moves it leads to are the same audited moves as before (ADR-0023, ADR-0035).

## Consequences

- Positive: "what was refused and why" is one choice, and trying the refused ones again is that choice and one button, with the count in front of the person.
- Positive: the counts say how far the work has got without reading the list.
- Positive: no change to anything outside the page.
- Negative: **the outcomes are kept only in the page.** Reloading the proposals starts again with no results and every booking *Not tried yet*, as before; a booking moved in an earlier session shows as not tried until its move is attempted (and is then reported as already there).
- Negative: **a retried booking changes outcome** (a refused booking that now moves leaves *Refused or failed* and appears under *Moved*), so the list under a filter shrinks as the person works. That is the intent, and it can surprise.
- Negative: **every row is still rendered** (ADR-0043); windowing very large flights is a separate step.
- Negative: the filter and search are not remembered across a reload.
- Not done: windowing of very large lists; a filter by reason for a refusal; saving results on the server.

## Verification

Unit tests for the filter and the counts (each outcome, groups left empty, passenger counts, the combination with the search, the bookings that need a person, the unchanged two-argument form); component tests for the page (the options and their counts, only the refused bookings shown with their reason and moved again when asked, the hidden ticked bookings reported and not moved, the bookings that need a person only under *All*, a fresh set starts on *All*). They failed before the code was written. The full frontend suite (441 tests), `tsc`, `eslint` and `next build` pass. The page was **not** clicked through in a browser, and no live check was run (no service changed).
