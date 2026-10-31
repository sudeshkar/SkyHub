# ADR-0043: Grouping and finding proposals on the operations page

**Status:** Accepted
**Date:** 2026-11-02

## Context

After a disruption the operations page lists one proposal per booking on the flight (ADR-0024), as a flat list with a checkbox on each. For a flight of 300 bookings that is 300 rows in the order the agent returned them. A person cannot see at a glance where the bookings are being sent, cannot untick everyone going to a flight they do not like without clicking each, and cannot find one booking when a traveller phones. ADR-0024 and ADR-0035 both name grouping and filtering as the follow-up. Bulk moving (ADR-0035) removed the cost of moving many; what is left is the cost of *deciding* about many.

## Options considered

1. **Group on the server** - the agent returns groups. A change to the agent's contract and service for something that is only a view of the same list.
2. **A table with column sorting and paging** - familiar, but approving is a decision about a set ("send these 40 to SK503"), and paging hides the very rows a person is about to approve.
3. **Group the list in the browser by where each booking is going, with selection and search that act on what is shown** - no contract change; the grouping is a pure function of the list the page already has.

## Decision

- **Option 3.** The proposals are grouped by **destination flight** (flight and date), largest group first, with bookings that **need a person** in a last group that has no checkboxes. Each group's heading says where it goes, how many bookings and how many people.
- **Selecting works on a group:** "Select all" and "Select none" for each group's bookings, so a person can drop everyone heading for one flight in a click.
- **Finding a booking:** a "Find a booking" box filters the rows by reference, ignoring case, and says when nothing matches.
- **What is shown is what is acted on.** The "Move N selected" button counts and moves only the selected bookings that are **visible**. A booking that is ticked but hidden by the search is neither counted nor moved, and the page says how many ticked bookings are hidden, so a search cannot quietly widen or narrow what is approved. Group buttons act on the visible rows of that group for the same reason.
- **Nothing about moving changed:** the same bulk calls (ADR-0035) in batches of 50, the same per-booking results, the staff member's own login (ADR-0024). Bookings already moved stay in their groups with their result.
- **Cost:** grouping and filtering are one pass over the list, O(n), recomputed as the box is typed in. No server call.

## Consequences

- Positive: where a flight's travellers are going, and untickling a whole destination, are visible and one click, instead of 300 rows to read.
- Positive: finding one booking is typing its reference.
- Positive: a search cannot change what the button moves without saying so.
- Positive: no change to the agent, the booking service or any contract.
- Negative: **every row is still rendered.** A flight of 500 bookings is 500 rows in the page; the grouping and search make it navigable but do not make it small. Paging or windowing the list is a separate step if a flight is ever much larger.
- Negative: **grouping is by destination only.** There is no grouping by party size, cabin, or reason, and no way to approve "all groups of fewer than 3 people". Cohort grouping in the agent (ADR-0022 follow-up) is a different, server-side change.
- Negative: the search is by reference only, not by name: names are not in the proposals (they are only on the booking page, ADR-0024).
- Negative: the choice of what is ticked is kept in the page, not saved; reloading the proposals starts again, as before.
- Follow-up: windowing for very large flights; filter by whether a booking is already moved or was refused.
