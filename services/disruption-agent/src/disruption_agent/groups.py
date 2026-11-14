"""Planning by group (ADR-0089). Bookings that fly the same route in the same window can use exactly the same flights, so
the model is asked once per group which flights to use, and code fills them.

Why: asked booking by booking, the model had to share out seats among dozens of bookings and mostly gave up (ADR-0080:
11% of a full day placed, against 56% for first-fit), and a 500-booking flight took about 13 long calls. A group is one
line, so a whole flight is usually one short call, and the arithmetic is done by code, exactly.

Cost: grouping is O(n). Packing a group is O(b log b) to sort its bookings, then O(b * f * rules) to try each booking on the
chosen flights in order (f is small: the model picks a handful)."""
from __future__ import annotations

from collections import Counter
from collections.abc import Iterable, MutableMapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime

from .guardrails import Facts, Rule
from .models import AffectedBooking, Alternative, Proposal

GroupKey = tuple[str, str, datetime, datetime | None]


@dataclass(frozen=True)
class Group:
    """Bookings that can move to the same flights: same route, same earliest departure, same latest arrival."""

    id: str
    bookings: tuple[AffectedBooking, ...]

    @property
    def first(self) -> AffectedBooking:
        return self.bookings[0]

    @property
    def passengers(self) -> int:
        return sum(b.passenger_count for b in self.bookings)

    @property
    def party_sizes(self) -> dict[int, int]:
        """How many bookings of each size: {1: 120, 2: 80, 4: 15}."""
        return dict(sorted(Counter(b.passenger_count for b in self.bookings).items()))


def _key(booking: AffectedBooking) -> GroupKey:
    leg = booking.disrupted_leg
    return leg.origin, leg.destination, booking.earliest_departure, booking.latest_arrival


def group_bookings(bookings: Iterable[AffectedBooking]) -> list[Group]:
    """Groups in the order their first booking appears, numbered G1, G2, ...; bookings keep their order inside a group."""
    members: dict[GroupKey, list[AffectedBooking]] = {}
    for booking in bookings:
        members.setdefault(_key(booking), []).append(booking)
    return [Group(id=f"G{number}", bookings=tuple(found)) for number, found in enumerate(members.values(), start=1)]


def pack(group: Group, flights: Sequence[Alternative], rules: Sequence[Rule], facts: Facts,
         taken: MutableMapping[tuple[str, date], int], why: str) -> list[Proposal]:
    """Fills the chosen flights, in the order given, with the group's bookings, **largest parties first** (the author's
    choice, ADR-0089): in a squeeze a family is placed before singles, and a party is never split. A booking goes on the
    first flight that passes every guardrail rule and still has room once `taken` (seats this run has given out so far, in
    every batch and by every group) is counted; `taken` is updated. A booking that fits nowhere goes to a person, with the reason. The guardrails
    check every proposal again afterwards, so this is planning, not the safety check."""
    proposals: list[Proposal] = []
    for booking in sorted(group.bookings, key=lambda b: (-b.passenger_count, b.locator)):
        choice = next((f for f in flights if _fits(booking, f, rules, facts, taken)), None)
        if choice is None:
            proposals.append(Proposal.needs_human(
                booking.locator, f"No seats left for {booking.passenger_count} together on the flights chosen for this group."))
            continue
        taken[choice.key] = taken.get(choice.key, 0) + booking.passenger_count
        proposals.append(Proposal(locator=booking.locator, action="REBOOK", flight_number=choice.flight_number,
                                  departure_date=choice.departure_date, rationale=why or "Chosen for this group."))
    return proposals


def _fits(booking: AffectedBooking, flight: Alternative, rules: Sequence[Rule], facts: Facts,
          taken: MutableMapping[tuple[str, date], int]) -> bool:
    if flight.seats_available - taken.get(flight.key, 0) < booking.passenger_count:
        return False    # every seat this run has given out; the capacity rule below is the guardrails' own count
    trial = Proposal(locator=booking.locator, action="REBOOK", flight_number=flight.flight_number,
                     departure_date=flight.departure_date)
    return all(rule.check(trial, booking, flight, facts) is None for rule in rules)
