# ADR-0068: Drawing only part of a long proposal list

**Status:** Accepted
**Date:** 2026-11-03

## Context

ADR-0043 grouped and searched the proposals after a disruption, and ADR-0067 filtered them by outcome, and both named the same limit: **every row is still drawn.** A flight of 500 or 850 bookings is that many rows in the page, each with a checkbox, so the page is slow to draw and slow to react to every tick, keystroke and batch that completes. The existing 120-row test already needs a five-second allowance "to draw on a busy machine".

## Options considered

1. **Page the list (page 1, 2, 3).** Rejected for the reason ADR-0043 gave: approving is a decision about a set ("send these 40 to SK503"), and a page boundary would cut a set in two and hide rows the person is about to approve.
2. **Draw only the rows in view as the person scrolls (a virtual list).** Rejected for now: it needs a library or careful measuring, it breaks the browser's find and the tests' queries, and the people on this page are working through a list, not browsing it.
3. **Draw the first rows and add more on request, leaving everything else about the list as it is.** Chosen: no new dependency, and the selection, the groups, the counts and the buttons do not change.

## Decision

- **The first 100 rows are drawn**, in the order the groups are listed, and "Show N more" draws the next 100 (or what is left: "Show 50 more"). `ROW_WINDOW` is the one number.
- **Only drawing is windowed.** Grouping, search, the outcome filter and their counts, what is ticked, and what "Move N selected" and each group's "All" and "None" act on all cover **every matching booking**, drawn or not. The page says so in words next to the count: "Showing 100 of 250 bookings. The buttons act on all 250 matching bookings, drawn or not." This is a change of emphasis from ADR-0043's "what is shown is what is acted on": the set that is acted on is still the one the search and the filter describe, and it is now stated beside the number rather than implied by every row being on the page.
- **Every group keeps its heading**, with its true size and its number of people, and its "All" and "None" buttons, even when none of its rows are drawn yet. A group with no row drawn has no empty list.
- **A new search or a new filter goes back to the first 100**, so a long list never keeps a large window the person did not ask for. A fresh set of proposals starts at 100 too.
- **A pure function does the cutting:** `windowGroups(groups, limit)` returns each group with the rows to draw, how many are drawn and how many there are, in one pass, `O(n)`. The page's other work is unchanged: one pass for the groups, one for the counts.
- **Cost at the largest case.** Drawing is now at most 100 rows at first and grows by 100 on request, instead of all 850. The page still does `O(n)` work in memory on each change to the list, which is small beside drawing.
- No change to the agent, the booking service or any contract, and no metric, alert or runbook entry: it changes only what the page draws.

## Consequences

- Positive: a flight of several hundred bookings opens at the speed of 100 rows, and each tick, keystroke and batch redraws 100 rows, not hundreds.
- Positive: nothing about what can be moved, or how, changed; the same audited moves in batches of 50 (ADR-0035).
- Negative: **a person can approve bookings they have not seen.** "Move 250 selected" moves 250 while 100 are drawn. The page says so beside the count, and the groups and the outcome filter still describe the set, but the rows of the rest are one click away, not in front of them. If that proves too easy to do by accident, the button could ask for a confirmation above a size, or refuse until everything has been drawn; neither is built.
- Negative: **the browser's find (Ctrl+F) only finds drawn rows.** The page's own search covers all of them.
- Negative: **"Show more" is not remembered**: a reload, a new set, a new search or a new filter goes back to 100.
- Negative: rows already drawn stay drawn, so a person who keeps pressing "Show more" ends at the old cost. That is a choice, not a limit.
- Not done: a virtual list, a size above which approving asks for a confirmation, and a "Show all" shortcut.

## Verification

Unit tests for `windowGroups` (everything drawn when the list fits, cut across groups in listed order, every group kept with its true size and people, empty list, a limit below one). Component tests for the page (100 rows of 250 with the count in words and the group's true size; 100 more at a time ending with the real remainder; nothing offered when the list fits in the window; moving all 250 selected in five batches although 100 are drawn; the window returning to 100 when the search or the filter changes). They failed before the code. The full frontend suite (451 tests), `tsc`, `eslint` and `next build` pass. The page was **not** clicked through in a browser, and no live check was run (no service changed).
