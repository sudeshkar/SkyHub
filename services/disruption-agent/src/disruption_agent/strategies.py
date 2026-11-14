"""How the agent asks for a plan: what the model is told, what it is shown, how it answers, and how that answer becomes one
proposal per booking. The agent runs either strategy unchanged (open/closed), and the guardrails check the result the same way.

* `PerBooking`: the model proposes a flight for every booking (ADR-0022). The rule-based first-fit planner answers this way.
* `ByGroup`: the model chooses flights for each group of bookings that need the same thing, and code fills them (ADR-0089)."""
from __future__ import annotations

from collections.abc import Iterator, MutableMapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from typing import Literal, Protocol

from pydantic import BaseModel, TypeAdapter, field_validator

from .groups import Group, group_bookings, pack
from .guardrails import Facts, Rule
from .llm import ToolSpec
from .models import AffectedBooking, Proposal, Rejection


def _time(moment) -> str | None:
    return moment.isoformat().replace("+00:00", "Z") if moment is not None else None


def _flight(flight: dict) -> dict:
    return {k: flight[k] for k in ("flight_number", "origin", "destination", "departure")}


def _chunks(items: Sequence, size: int) -> Iterator[Sequence]:
    for start in range(0, len(items), size):
        yield items[start:start + size]


@dataclass(frozen=True)
class Batch:
    """One request to the model, and the bookings whose answers it must produce."""

    request: dict
    bookings: Sequence[AffectedBooking]
    groups: Sequence[Group] = field(default_factory=tuple)


class Strategy(Protocol):
    system_prompt: str
    submit: ToolSpec

    def batches(self, flight: dict, bookings: Sequence[AffectedBooking], size: int) -> Iterator[Batch]: ...

    def answer(self, batch: Batch, arguments: dict, facts: Facts, rules: Sequence[Rule],
               taken: MutableMapping[tuple[str, date], int]) -> tuple[list[Proposal], list[Rejection]]:
        """The final answer as proposals (and anything refused on the way). Raises pydantic's ValidationError when the answer
        is malformed, so the model can be asked again."""
        ...


# ---- one proposal per booking (ADR-0022) ---------------------------------------------------------------------------------------

SUBMIT = "submit_proposals"

SUBMIT_SPEC = ToolSpec(
    name=SUBMIT,
    description="Give your final answer: one proposal for every booking in the request. Call this exactly once.",
    parameters={
        "type": "object",
        "properties": {"proposals": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "locator": {"type": "string"},
                "action": {"type": "string", "enum": ["REBOOK", "NEEDS_HUMAN"]},
                "flight_number": {"type": "string", "description": "required for REBOOK; must come from find_alternatives"},
                "departure_date": {"type": "string", "description": "YYYY-MM-DD, required for REBOOK"},
                "rationale": {"type": "string", "description": "one short sentence"},
            },
            "required": ["locator", "action"]}}},
        "required": ["proposals"]})

SYSTEM_PROMPT = """You help airline operations staff re-accommodate travellers when a flight is delayed or cancelled.
You are given a batch of affected bookings. Each has a reference, how many passengers it holds, the route to fly, and a
window: the earliest it can depart and, if the traveller has a flight to catch afterwards, the latest it can arrive.

Rules:
- Find flights with the find_alternatives tool (use each booking's window and passenger count). Propose ONLY flights that
  tool returned. Never invent a flight, a time or a seat count.
- A flight must leave no earlier than the booking's earliest departure, land no later than its latest arrival, and have
  enough free seats for ALL the booking's passengers. Free seats are shared: do not put more passengers on a flight than
  it has seats, counting every booking you assign to it.
- Never propose the disrupted flight itself; it is not offered, and it is the one being left.
- Prefer the earliest flight that fits. If none fits, use NEEDS_HUMAN and say why in one sentence.
- Finish by calling submit_proposals once, with exactly one proposal for every booking in the request.
Staff approve or change your proposals; nothing happens automatically."""


class PerBooking:
    system_prompt = SYSTEM_PROMPT
    submit = SUBMIT_SPEC

    def batches(self, flight: dict, bookings: Sequence[AffectedBooking], size: int) -> Iterator[Batch]:
        for chunk in _chunks(bookings, size):
            yield Batch(request={"disrupted_flight": _flight(flight), "bookings": [{
                "locator": b.locator, "passengers": b.passenger_count,
                "origin": b.disrupted_leg.origin, "destination": b.disrupted_leg.destination,
                "earliest_departure": _time(b.earliest_departure), "latest_arrival": _time(b.latest_arrival),
            } for b in chunk]}, bookings=chunk)

    def answer(self, batch, arguments, facts, rules, taken):
        return TypeAdapter(list[Proposal]).validate_python(arguments.get("proposals", [])), []


# ---- flights per group, filled by code (ADR-0089) ------------------------------------------------------------------------------

SUBMIT_PLAN = "submit_plan"

SUBMIT_PLAN_SPEC = ToolSpec(
    name=SUBMIT_PLAN,
    description="Give your final answer: one plan for every group in the request. Call this exactly once.",
    parameters={
        "type": "object",
        "properties": {"plans": {"type": "array", "items": {
            "type": "object",
            "properties": {
                "group": {"type": "string", "description": "the group's id, such as G1"},
                "action": {"type": "string", "enum": ["REBOOK", "NEEDS_HUMAN"]},
                "flights": {"type": "array", "description": "for REBOOK: the flights to fill, in order of preference; each "
                                                            "must come from find_alternatives",
                            "items": {"type": "object", "properties": {
                                "flight_number": {"type": "string"},
                                "departure_date": {"type": "string", "description": "YYYY-MM-DD"}},
                                "required": ["flight_number", "departure_date"]}},
                "rationale": {"type": "string", "description": "one short sentence"},
            },
            "required": ["group", "action"]}}},
        "required": ["plans"]})

GROUP_PROMPT = """You help airline operations staff re-accommodate travellers when a flight is delayed or cancelled.
You are given groups of affected bookings. Every booking in a group flies the same route in the same window (the earliest it
can depart and, if it has a flight to catch afterwards, the latest it can arrive), so a whole group can use the same
flights. You choose the flights; code then fills them, largest parties first, counting every seat. You never assign
individual bookings or seats.

Rules:
- For each group, call find_alternatives with its origin, destination, earliest departure, latest arrival (if any) and
  passengers=1. Choose ONLY flights that tool returned. Never invent a flight, a time or a seat count.
- A chosen flight must leave no earlier than the group's earliest departure and land no later than its latest arrival.
- Never choose the disrupted flight itself; it is not offered, and it is the one being left.
- List every suitable flight, earliest first: one flight is rarely enough for a large group, and seats are shared between
  groups.
- Choose flights even when they cannot take the whole group. Code moves as many bookings as the seats allow and hands only
  the rest to a person, so "not enough seats for everyone" is never a reason for NEEDS_HUMAN. Use NEEDS_HUMAN only when the
  search returned no flight at all inside the group's window, and say why in one sentence.
- Finish by calling submit_plan once, with exactly one plan for every group in the request.
Staff approve or change every move; nothing happens automatically."""


class FlightChoice(BaseModel):
    flight_number: str
    departure_date: date

    @field_validator("flight_number")
    @classmethod
    def _tidy(cls, value: str) -> str:
        return value.strip().upper()


class GroupPlan(BaseModel):
    group: str
    action: Literal["REBOOK", "NEEDS_HUMAN"]
    flights: list[FlightChoice] = []
    rationale: str = ""


class ByGroup:
    system_prompt = GROUP_PROMPT
    submit = SUBMIT_PLAN_SPEC

    def batches(self, flight: dict, bookings: Sequence[AffectedBooking], size: int) -> Iterator[Batch]:
        for chunk in _chunks(group_bookings(bookings), size):
            yield Batch(request={"disrupted_flight": _flight(flight), "groups": [{
                "group": g.id, "bookings": len(g.bookings), "passengers": g.passengers,
                "party_sizes": {str(size): count for size, count in g.party_sizes.items()},
                "origin": g.first.disrupted_leg.origin, "destination": g.first.disrupted_leg.destination,
                "earliest_departure": _time(g.first.earliest_departure), "latest_arrival": _time(g.first.latest_arrival),
            } for g in chunk]}, bookings=[b for g in chunk for b in g.bookings], groups=chunk)

    def answer(self, batch, arguments, facts, rules, taken):
        plans = TypeAdapter(list[GroupPlan]).validate_python(arguments.get("plans", []))
        by_id: dict[str, GroupPlan] = {}
        rejections: list[Rejection] = []
        for plan in plans:
            if plan.group not in {g.id for g in batch.groups}:
                rejections.append(Rejection(locator=f"group {plan.group}", reason="not one of the groups in this request"))
            elif plan.group in by_id:
                rejections.append(Rejection(locator=f"group {plan.group}", reason="a second plan for the same group was ignored"))
            else:
                by_id[plan.group] = plan
        proposals: list[Proposal] = []
        for group in batch.groups:
            plan = by_id.get(group.id)
            if plan is None or plan.action == "NEEDS_HUMAN":
                why = (plan.rationale if plan else "") or "no plan was made for this group"
                proposals.extend(Proposal.needs_human(b.locator, why) for b in group.bookings)
                continue
            flights = []
            for choice in plan.flights:
                found = facts.alternatives.get((choice.flight_number, choice.departure_date))
                if found is None:
                    rejections.append(Rejection(locator=f"group {group.id}", reason=(
                        f"{choice.flight_number} on {choice.departure_date} was never returned by a flight search, so it was not used")))
                else:
                    flights.append(found)
            proposals.extend(pack(group, flights, rules, facts, taken, plan.rationale))
        return proposals, rejections
