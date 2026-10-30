"""Carrying out proposals, one person's answer at a time (ADR-0023).

The agent only proposes. This module is the only place anything is changed, and it changes nothing without a person
saying yes to that booking. The move is a staff action on the booking service, which re-checks every rule itself; it is
deliberately not an MCP tool, because a tool any connected model could call would not need a person at all."""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum
from typing import Literal, Protocol

from .clients import BookingClient, ServiceError
from .models import Proposal, Report


class Decision(Enum):
    YES = "yes"
    NO = "no"
    ALL = "all"      # yes to this one and to every remaining one in this run
    QUIT = "quit"    # stop here: this one and the rest are left alone


class Approver(Protocol):
    """Whoever answers. A console prompt is one; a web screen would be another, with no change to the Applier."""

    def approve(self, proposal: Proposal) -> Decision: ...


class ConsoleApprover:
    """Asks on the terminal. Anything it does not understand is asked again; an empty answer is a no."""

    _ANSWERS = {"y": Decision.YES, "yes": Decision.YES, "n": Decision.NO, "no": Decision.NO, "": Decision.NO,
                "a": Decision.ALL, "all": Decision.ALL, "q": Decision.QUIT, "quit": Decision.QUIT}

    def __init__(self, input_fn: Callable[[str], str] = input, output_fn: Callable[[str], None] = print):
        self._input = input_fn
        self._output = output_fn

    def approve(self, proposal: Proposal) -> Decision:
        self._output(f"{proposal.locator}: move to {proposal.flight_number} on {proposal.departure_date}. {proposal.rationale}")
        while True:
            decision = self._ANSWERS.get(self._input("  Approve? [y]es / [n]o / [a]ll the rest / [q]uit: ").strip().lower())
            if decision is not None:
                return decision
            self._output("  Please answer y, n, a or q.")


@dataclass(frozen=True)
class Outcome:
    locator: str
    status: Literal["moved", "unchanged", "declined", "failed", "skipped", "stopped"]
    detail: str = ""


class Applier:
    """Walks the report once: O(proposals) calls to the approver and at most one booking-service call per approval."""

    def __init__(self, booking: BookingClient, approver: Approver):
        self._booking = booking
        self._approver = approver

    def apply(self, report: Report) -> list[Outcome]:
        outcomes: list[Outcome] = []
        approve_rest = False
        stopped = False
        for proposal in report.proposals:
            if proposal.action != "REBOOK" or proposal.segment_index is None:
                # Needs a person anyway, or never went through the guardrails (it has no verified leg to move).
                outcomes.append(Outcome(proposal.locator, "skipped", proposal.rationale))
                continue
            if stopped:
                outcomes.append(Outcome(proposal.locator, "stopped"))
                continue
            if not approve_rest:
                decision = self._approver.approve(proposal)
                if decision is Decision.QUIT:
                    stopped = True
                    outcomes.append(Outcome(proposal.locator, "stopped"))
                    continue
                if decision is Decision.NO:
                    outcomes.append(Outcome(proposal.locator, "declined"))
                    continue
                approve_rest = decision is Decision.ALL
            outcomes.append(self._move(proposal))
        return outcomes

    def _move(self, proposal: Proposal) -> Outcome:
        try:
            moved = self._booking.rebook(proposal.locator, proposal.segment_index, proposal.flight_number, proposal.departure_date)
        except ServiceError as problem:
            return Outcome(proposal.locator, "failed", str(problem))
        seats = ", ".join(moved.get("seats", []))
        return Outcome(proposal.locator, "moved" if moved["changed"] else "unchanged", f"seats {seats}" if seats else "")


def summary(outcomes: list[Outcome]) -> str:
    lines = [f"  {o.locator}: {o.status}{(' - ' + o.detail) if o.detail else ''}" for o in outcomes]
    counts = {status: sum(1 for o in outcomes if o.status == status) for status in ("moved", "unchanged", "declined", "failed", "skipped", "stopped")}
    head = ", ".join(f"{n} {status}" for status, n in counts.items() if n)
    return "\n".join([f"Result: {head or 'nothing to do'}.", *lines])
