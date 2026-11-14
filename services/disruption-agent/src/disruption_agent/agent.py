"""The agent: gathers the facts with code, asks the model for proposals in bounded batches, and lets the guardrails decide
what is believed. The model never sees a credential, a name or an email address, and nothing it says is carried out.

How the model is asked is a strategy (strategies.py): a proposal per booking (ADR-0022), or flights per group of bookings
that code then fills (ADR-0089). Either way every booking ends up with exactly one checked proposal."""
from __future__ import annotations

import asyncio
import json
from collections.abc import Callable, Sequence
from datetime import date

from pydantic import ValidationError

from .clients import ServiceError
from .guardrails import DisruptedFlightHidden, Facts, GuardrailPipeline, Policy, ResultAdapter, default_policy
from .llm import INVALID_JSON, ChatModel, ToolCall, ToolSpec
from .models import AffectedBooking, Proposal, Rejection, Report
from .strategies import SUBMIT, SUBMIT_SPEC, SYSTEM_PROMPT, Batch, PerBooking, Strategy
from .toolbox import ToolBox

__all__ = ["DisruptionAgent", "SUBMIT", "SUBMIT_SPEC", "SYSTEM_PROMPT"]

IMPACT_PAGE = 100


def _tool_message(call: ToolCall, payload: dict) -> dict:
    return {"role": "tool", "tool_call_id": call.id, "content": json.dumps(payload, default=str)}


class DisruptionAgent:
    """Dependencies are injected: the model (any `ChatModel`), the tools (any `ToolBox`), the policy (any rules and result
    adapters) and the strategy (how the model is asked), so none of them is ever edited to try another."""

    def __init__(self, model: ChatModel, tools: ToolBox, *, batch_size: int = 40, max_steps: int = 8,
                 llm_tools: Sequence[str] = ("find_alternatives",), policy: Callable[[], Policy] = default_policy,
                 strategy: Strategy | None = None):
        if batch_size < 1 or max_steps < 1:
            raise ValueError("batch_size and max_steps must be at least 1")
        self._model = model
        self._tools = tools
        self._batch_size = batch_size
        self._max_steps = max_steps
        self._llm_tools = tuple(llm_tools)
        self._policy = policy
        self._strategy = strategy or PerBooking()

    async def run(self, flight_number: str, day: date) -> Report:
        flight, bookings = await self._affected(flight_number, day)
        if not bookings:
            return Report(flight_number=flight_number, departure_date=day, bookings_total=0, proposals=[])
        facts = Facts((flight_number, day))
        facts.add_bookings(bookings)
        policy = self._policy()                              # one policy per run: seat counts carry across batches
        pipeline = GuardrailPipeline(facts, policy.rules)
        adapters = [*policy.adapters, DisruptedFlightHidden(facts.disrupted)]    # whatever the policy, never offer the flight being left
        offered = await self._offered_tools()
        taken: dict = {}                                     # seats this run has planned onto each flight, across batches
        report = Report(flight_number=flight_number, departure_date=day, bookings_total=len(bookings), proposals=[])
        for batch in self._strategy.batches(flight, bookings, self._batch_size):    # bounds the model's context however big the flight is
            submitted, refused = await self._propose(batch, facts, offered, adapters, policy, taken)
            verified = pipeline.verify(submitted, batch.bookings)
            report.proposals.extend(verified.proposals)
            report.rejections.extend([*refused, *verified.rejections])
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
        return [available[name] for name in self._llm_tools if name in available] + [self._strategy.submit]

    async def _propose(self, batch: Batch, facts: Facts, tools: list[ToolSpec], adapters: Sequence[ResultAdapter],
                       policy: Policy, taken: dict) -> tuple[list[Proposal], list[Rejection]]:
        messages: list[dict] = [{"role": "system", "content": self._strategy.system_prompt},
                                {"role": "user", "content": json.dumps(batch.request)}]
        allowed = {spec.name for spec in tools}
        for _ in range(self._max_steps):
            turn = await asyncio.to_thread(self._model.complete, messages, tools)
            if not turn.tool_calls:
                return [], []    # it stopped without answering: the guardrails hand every booking to a person
            messages.append({"role": "assistant", "content": turn.content, "tool_calls": [
                {"id": c.id, "type": "function", "function": {"name": c.name, "arguments": json.dumps(c.arguments)}}
                for c in turn.tool_calls]})
            for call in turn.tool_calls:
                answer = await self._answer(call, allowed, batch, facts, adapters, policy, taken)
                if isinstance(answer, tuple):
                    return answer
                messages.append(_tool_message(call, answer))
        return [], []            # out of steps: same safe outcome

    async def _answer(self, call: ToolCall, allowed: set[str], batch: Batch, facts: Facts, adapters: Sequence[ResultAdapter],
                      policy: Policy, taken: dict) -> tuple[list[Proposal], list[Rejection]] | dict:
        """A final answer (the proposals and anything refused on the way), or a tool result / error message to show the model."""
        if INVALID_JSON in call.arguments:
            return {"error": f"The arguments for {call.name} were not valid JSON. Send a JSON object."}
        if call.name not in allowed:
            return {"error": f"There is no tool called {call.name}. Use one of: {', '.join(sorted(allowed))}"}
        if call.name == self._strategy.submit.name:
            try:
                return self._strategy.answer(batch, call.arguments, facts, policy.rules, taken)
            except ValidationError as problem:
                return {"error": f"Your answer was not valid: {problem.errors(include_url=False, include_context=False)[:3]}"}
        result = await self._tools.call(call.name, call.arguments)
        facts.observe(call.name, result)        # the guardrails learn the truth ...
        for adapter in adapters:                # ... the model is shown what is still free in this run
            result = adapter.adapt(call.name, call.arguments, result)
        return result
