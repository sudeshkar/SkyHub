"""Code that checks the model's work. The model proposes; nothing it says is believed until these rules agree.

Each rule is a small class with one job (single responsibility). The pipeline runs whatever rules it is given, so adding
a safety check means writing one new rule and listing it, with no edit to any existing rule or to the pipeline
(open/closed). Cost: every proposal does O(1) dictionary lookups and runs each rule once, so verifying n proposals is
O(n * rules), never O(n^2); seat counts are kept in a ledger rather than recomputed by rescanning earlier proposals."""
from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date
from typing import Protocol

from .models import AffectedBooking, Alternative, Proposal, Rejection


class Facts:
    """What the tools actually told us. A proposal may only use something in here."""

    def __init__(self, disrupted: tuple[str, date]):
        self.disrupted = disrupted
        self.bookings: dict[str, AffectedBooking] = {}
        self.alternatives: dict[tuple[str, date], Alternative] = {}

    def add_bookings(self, bookings: Iterable[AffectedBooking]) -> None:
        self.bookings.update((b.locator, b) for b in bookings)

    def add_alternatives(self, alternatives: Iterable[Alternative]) -> None:
        self.alternatives.update((a.key, a) for a in alternatives)

    def observe(self, tool_name: str, result: dict) -> None:
        """Called with every tool result. A new kind of tool that yields facts registers a method below."""
        observer = self._OBSERVERS.get(tool_name)
        if observer is not None and "error" not in result:
            observer(self, result)

    def _observe_alternatives(self, result: dict) -> None:
        self.add_alternatives(Alternative.model_validate(item) for item in result.get("alternatives", []))

    _OBSERVERS = {"find_alternatives": _observe_alternatives}


class Rule(ABC):
    """One safety check on one proposal to rebook a booking onto a flight."""

    @abstractmethod
    def check(self, proposal: Proposal, booking: AffectedBooking, alternative: Alternative, facts: Facts) -> str | None:
        """None when the proposal passes; otherwise the reason it is refused, written for a person to read."""

    def commit(self, proposal: Proposal, booking: AffectedBooking, alternative: Alternative) -> None:
        """Called once a proposal has passed every rule. Stateful rules (seat counts) record it here."""


class NotTheDisruptedFlight(Rule):
    def check(self, proposal, booking, alternative, facts):
        if alternative.key == facts.disrupted:
            return f"{alternative.flight_number} is the disrupted flight itself"
        return None


class SameRoute(Rule):
    def check(self, proposal, booking, alternative, facts):
        leg = booking.disrupted_leg
        if (alternative.origin, alternative.destination) != (leg.origin, leg.destination):
            return (f"{alternative.flight_number} flies {alternative.origin} to {alternative.destination}, "
                    f"not the disrupted route {leg.origin} to {leg.destination}")
        return None


class WithinWindow(Rule):
    def check(self, proposal, booking, alternative, facts):
        if alternative.departure < booking.earliest_departure:
            return f"{alternative.flight_number} leaves before the traveller can ({booking.earliest_departure:%Y-%m-%d %H:%M} UTC)"
        if booking.latest_arrival is not None and alternative.arrival > booking.latest_arrival:
            return (f"{alternative.flight_number} lands after {booking.latest_arrival:%Y-%m-%d %H:%M} UTC, "
                    "too late for the traveller's next flight")
        return None


class SeatLedger:
    """How many seats this run has already given out, per dated flight. One ledger is shared by the rule that enforces
    capacity and by whatever tells the model what is still free, so the two can never disagree. O(1) per operation."""

    def __init__(self) -> None:
        self._taken: dict[tuple[str, date], int] = {}

    def taken(self, key: tuple[str, date]) -> int:
        return self._taken.get(key, 0)

    def remaining(self, alternative: Alternative) -> int:
        return alternative.seats_available - self.taken(alternative.key)

    def take(self, alternative: Alternative, passengers: int) -> None:
        self._taken[alternative.key] = self.taken(alternative.key) + passengers


class SeatCapacity(Rule):
    """Never puts more passengers on a flight than it has free seats, counting everyone already accepted in this run."""

    def __init__(self, ledger: SeatLedger) -> None:
        self._ledger = ledger

    def check(self, proposal, booking, alternative, facts):
        left = self._ledger.remaining(alternative)
        if booking.passenger_count > left:
            return f"only {left} seats left on {alternative.flight_number}, {booking.passenger_count} needed"
        return None

    def commit(self, proposal, booking, alternative):
        self._ledger.take(alternative, booking.passenger_count)


class ResultAdapter(Protocol):
    """Changes what the model is shown of a tool result, never what the guardrails know."""

    def adapt(self, tool_name: str, arguments: dict, result: dict) -> dict: ...


class SeatAwareResults:
    """Shows the model the seats still free *in this run*: a flight's count is reduced by what earlier batches took, and a
    flight too full for the group being asked about is left out. Without it each batch would keep proposing flights
    that earlier batches had already filled, and the guardrails would refuse them one by one. O(alternatives)."""

    def __init__(self, ledger: SeatLedger) -> None:
        self._ledger = ledger

    def adapt(self, tool_name: str, arguments: dict, result: dict) -> dict:
        if tool_name != "find_alternatives" or "alternatives" not in result:
            return result
        needed = int(arguments.get("passengers", 1))
        shown = []
        for item in result["alternatives"]:
            left = item["seats_available"] - self._ledger.taken((item["flight_number"], date.fromisoformat(item["departure_date"])))
            if left >= needed:
                shown.append({**item, "seats_available": left})
        return {**result, "alternatives": shown}


@dataclass(frozen=True)
class Policy:
    """Everything that decides what is believed and what the model is told, built fresh for each run."""

    rules: list[Rule]
    adapters: list[ResultAdapter]


def default_rules(ledger: SeatLedger | None = None) -> list[Rule]:
    return [NotTheDisruptedFlight(), SameRoute(), WithinWindow(), SeatCapacity(ledger or SeatLedger())]


def default_policy() -> Policy:
    """A fresh ledger for each run, shared by the capacity rule and the seat-aware results."""
    ledger = SeatLedger()
    return Policy(rules=default_rules(ledger), adapters=[SeatAwareResults(ledger)])


@dataclass
class Verified:
    proposals: list[Proposal] = field(default_factory=list)      # exactly one per booking in the batch, in batch order
    rejections: list[Rejection] = field(default_factory=list)


class GuardrailPipeline:
    """Turns whatever the model submitted into a safe answer for a batch of bookings: every booking gets exactly one
    proposal, anything not verified becomes "needs a person", and every refusal is recorded with its reason."""

    def __init__(self, facts: Facts, rules: Sequence[Rule]):
        self._facts = facts
        self._rules = list(rules)

    def verify(self, submitted: Iterable[Proposal], batch: Sequence[AffectedBooking]) -> Verified:
        in_batch = {b.locator for b in batch}
        chosen: dict[str, Proposal] = {}
        result = Verified()
        for proposal in submitted:
            locator = proposal.locator
            if locator not in self._facts.bookings or locator not in in_batch:
                result.rejections.append(Rejection(locator=locator, reason="not one of the affected bookings in this request"))
            elif locator in chosen:
                result.rejections.append(Rejection(locator=locator, reason="a second proposal for the same booking was ignored"))
            else:
                chosen[locator] = self._decide(proposal, result)
        for booking in batch:                      # every booking gets an answer, in a stable order
            result.proposals.append(chosen.get(booking.locator) or Proposal.needs_human(
                booking.locator, "no proposal was made for this booking"))
        return result

    def _decide(self, proposal: Proposal, result: Verified) -> Proposal:
        if proposal.action != "REBOOK":
            return proposal
        booking = self._facts.bookings[proposal.locator]
        alternative = self._facts.alternatives.get((proposal.flight_number, proposal.departure_date))
        if alternative is None:
            return self._refuse(proposal, result, f"{proposal.flight_number} on {proposal.departure_date} was never returned "
                                                  "by a flight search, so it cannot be proposed")
        for rule in self._rules:
            reason = rule.check(proposal, booking, alternative, self._facts)
            if reason is not None:
                return self._refuse(proposal, result, reason)
        for rule in self._rules:
            rule.commit(proposal, booking, alternative)
        return proposal

    @staticmethod
    def _refuse(proposal: Proposal, result: Verified, reason: str) -> Proposal:
        result.rejections.append(Rejection(locator=proposal.locator, reason=reason))
        return Proposal.needs_human(proposal.locator, reason)
