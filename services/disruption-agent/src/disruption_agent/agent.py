"""The agent: gathers the facts with code, asks the model for proposals in bounded batches, and lets the guardrails decide
what is believed. The model never sees a credential, a name or an email address, and nothing it says is carried out."""
from __future__ import annotations

import asyncio
import json
from collections.abc import Callable, Iterator, Sequence
from datetime import date

from pydantic import TypeAdapter, ValidationError

from .clients import ServiceError
from .guardrails import Facts, GuardrailPipeline, Policy, ResultAdapter, default_policy
from .llm import INVALID_JSON, ChatModel, ToolCall, ToolSpec
from .models import AffectedBooking, Proposal, Report
from .toolbox import ToolBox

SUBMIT = "submit_proposals"
IMPACT_PAGE = 100

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
            "required": ["locator", "action", "rationale"]}}},
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
- Prefer the earliest flight that fits. If none fits, use NEEDS_HUMAN and say why in one sentence.
- Finish by calling submit_proposals once, with exactly one proposal for every booking in the request.
Staff approve or change your proposals; nothing happens automatically."""


def _chunks(items: Sequence, size: int) -> Iterator[Sequence]:
    for start in range(0, len(items), size):
        yield items[start:start + size]


def _tool_message(call: ToolCall, payload: dict) -> dict:
    return {"role": "tool", "tool_call_id": call.id, "content": json.dumps(payload, default=str)}


class DisruptionAgent:
    """Dependencies are injected: the model (any `ChatModel`), the tools (any `ToolBox`) and the policy (any rules and
    result adapters), so none of them is ever edited to try another."""

    def __init__(self, model: ChatModel, tools: ToolBox, *, batch_size: int = 40, max_steps: int = 8,
                 llm_tools: Sequence[str] = ("find_alternatives",), policy: Callable[[], Policy] = default_policy):
        if batch_size < 1 or max_steps < 1:
            raise ValueError("batch_size and max_steps must be at least 1")
        self._model = model
        self._tools = tools
        self._batch_size = batch_size
        self._max_steps = max_steps
        self._llm_tools = tuple(llm_tools)
        self._policy = policy

    async def run(self, flight_number: str, day: date) -> Report:
        flight, bookings = await self._affected(flight_number, day)
        if not bookings:
            return Report(flight_number=flight_number, departure_date=day, bookings_total=0, proposals=[])
        facts = Facts((flight_number, day))
        facts.add_bookings(bookings)
        policy = self._policy()                              # one policy per run: seat counts carry across batches
        pipeline = GuardrailPipeline(facts, policy.rules)
        offered = await self._offered_tools()
        report = Report(flight_number=flight_number, departure_date=day, bookings_total=len(bookings), proposals=[])
        for batch in _chunks(bookings, self._batch_size):    # bounds the model's context however big the flight is
            submitted = await self._propose(flight, batch, facts, offered, policy.adapters)
            verified = pipeline.verify(submitted, batch)
            report.proposals.extend(verified.proposals)
            report.rejections.extend(verified.rejections)
        return report

    async def _affected(self, flight_number: str, day: date) -> tuple[dict, list[AffectedBooking]]:
        bookings: list[AffectedBooking] = []
        flight: dict = {}
        offset: int | None = 0
        while offset is not None:    # the code pages through every booking itself: the model is never trusted to
            page = await self._tools.call("get_disruption_impact", {
                "flight_number": flight_number, "departure_date": day.isoformat(), "offset": offset, "limit": IMPACT_PAGE})
            if "error" in page:
                raise ServiceError(page["error"])
            flight = page["flight"]
            bookings.extend(AffectedBooking.model_validate(b) for b in page["bookings"])
            offset = page["next_offset"]
        return flight, bookings

    async def _offered_tools(self) -> list[ToolSpec]:
        available = {spec.name: spec for spec in await self._tools.specs()}
        return [available[name] for name in self._llm_tools if name in available] + [SUBMIT_SPEC]

    async def _propose(self, flight: dict, batch: Sequence[AffectedBooking], facts: Facts, tools: list[ToolSpec],
                       adapters: Sequence[ResultAdapter]) -> list[Proposal]:
        messages: list[dict] = [{"role": "system", "content": SYSTEM_PROMPT},
                                {"role": "user", "content": json.dumps(self._request(flight, batch))}]
        allowed = {spec.name for spec in tools}
        for _ in range(self._max_steps):
            turn = await asyncio.to_thread(self._model.complete, messages, tools)
            if not turn.tool_calls:
                return []    # it stopped without answering: the guardrails hand every booking to a person
            messages.append({"role": "assistant", "content": turn.content, "tool_calls": [
                {"id": c.id, "type": "function", "function": {"name": c.name, "arguments": json.dumps(c.arguments)}}
                for c in turn.tool_calls]})
            for call in turn.tool_calls:
                answer = await self._answer(call, allowed, facts, adapters)
                if isinstance(answer, list):
                    return answer
                messages.append(_tool_message(call, answer))
        return []            # out of steps: same safe outcome

    async def _answer(self, call: ToolCall, allowed: set[str], facts: Facts,
                      adapters: Sequence[ResultAdapter]) -> list[Proposal] | dict:
        """A final answer (the proposals), or a tool result / error message to show the model."""
        if INVALID_JSON in call.arguments:
            return {"error": f"The arguments for {call.name} were not valid JSON. Send a JSON object."}
        if call.name not in allowed:
            return {"error": f"There is no tool called {call.name}. Use one of: {', '.join(sorted(allowed))}"}
        if call.name == SUBMIT:
            try:
                return TypeAdapter(list[Proposal]).validate_python(call.arguments.get("proposals", []))
            except ValidationError as problem:
                return {"error": f"Your proposals were not valid: {problem.errors(include_url=False, include_context=False)[:3]}"}
        result = await self._tools.call(call.name, call.arguments)
        facts.observe(call.name, result)        # the guardrails learn the truth ...
        for adapter in adapters:                # ... the model is shown what is still free in this run
            result = adapter.adapt(call.name, call.arguments, result)
        return result

    @staticmethod
    def _request(flight: dict, batch: Sequence[AffectedBooking]) -> dict:
        return {
            "disrupted_flight": {k: flight[k] for k in ("flight_number", "origin", "destination", "departure")},
            "bookings": [{
                "locator": b.locator, "passengers": b.passenger_count,
                "origin": b.disrupted_leg.origin, "destination": b.disrupted_leg.destination,
                "earliest_departure": b.earliest_departure.isoformat().replace("+00:00", "Z"),
                "latest_arrival": b.latest_arrival.isoformat().replace("+00:00", "Z") if b.latest_arrival else None,
            } for b in batch]}
