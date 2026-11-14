"""A deliberately bad model: it proposes a random mix of good, wrong and invented answers. If the guardrails are sound, the
answer a person sees is safe whatever this says."""
from __future__ import annotations

import json
import random

from disruption_agent.llm import ToolCall, ToolSpec, Turn

REAL = [("SK501", "2026-11-02"), ("SK502", "2026-11-02"), ("SK503", "2026-11-02"), ("SK505", "2026-11-02"),
        ("SK501", "2026-11-03")]


class ChaosModel:
    def __init__(self, rng: random.Random):
        self._rng = rng

    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn:
        if not any(m["role"] == "tool" for m in messages):    # learn the real flights first, like a diligent model
            return Turn(content=None, tool_calls=[ToolCall(id="look", name="find_alternatives", arguments={
                "origin": "DXB", "destination": "LHR", "not_before": "2026-11-02T00:00:00Z", "passengers": 1})])
        proposals = []
        for booking in json.loads(messages[1]["content"])["bookings"]:
            roll = self._rng.random()
            locator = booking["locator"]
            if roll < 0.10:
                continue                                                                    # forgets a booking
            if roll < 0.20:
                proposals.append({"locator": locator, "action": "NEEDS_HUMAN", "rationale": "unsure"})
                continue
            flight, day = self._rng.choice(REAL)
            if roll < 0.30:
                flight = f"SK9{self._rng.randint(10, 99)}"                                  # invents a flight
            elif roll < 0.38:
                flight, day = "SK501", "2026-11-02"                                         # the disrupted flight
            proposals.append({"locator": locator, "action": "REBOOK", "flight_number": flight, "departure_date": day,
                              "rationale": "chaos"})
            if roll > 0.94:
                proposals.append({"locator": locator, "action": "REBOOK", "flight_number": flight, "departure_date": day,
                                  "rationale": "again"})                                    # proposes twice
        if self._rng.random() < 0.3:
            proposals.append({"locator": "ZZZ999", "action": "REBOOK", "flight_number": "SK503",
                              "departure_date": "2026-11-02", "rationale": "a booking that does not exist"})
        return Turn(content=None, tool_calls=[ToolCall(id="answer", name="submit_proposals", arguments={"proposals": proposals})])


class GroupChaosModel:
    """The same bad model for planning by group (ADR-0089): random flights, invented flights, the disrupted flight, flights
    outside a group's window, duplicate and made-up groups, forgotten groups. Code fills whatever it chooses, so this tests
    that the packing and the guardrails together still give a safe answer."""

    def __init__(self, rng: random.Random):
        self._rng = rng

    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn:
        if not any(m["role"] == "tool" for m in messages):
            return Turn(content=None, tool_calls=[ToolCall(id="look", name="find_alternatives", arguments={
                "origin": "DXB", "destination": "LHR", "not_before": "2026-11-02T00:00:00Z", "passengers": 1})])
        plans = []
        for group in json.loads(messages[1]["content"])["groups"]:
            roll = self._rng.random()
            if roll < 0.10:
                continue                                                                    # forgets a group
            if roll < 0.20:
                plans.append({"group": group["group"], "action": "NEEDS_HUMAN", "rationale": "unsure"})
                continue
            flights = []
            for _ in range(self._rng.randint(0, 5)):
                flight, day = self._rng.choice(REAL)                                       # includes the disrupted flight
                if self._rng.random() < 0.2:
                    flight = f"SK9{self._rng.randint(10, 99)}"                              # invents a flight
                flights.append({"flight_number": flight, "departure_date": day})
            plans.append({"group": group["group"], "action": "REBOOK", "flights": flights, "rationale": "chaos"})
            if roll > 0.9:
                plans.append({"group": group["group"], "action": "REBOOK", "flights": flights, "rationale": "again"})
        if self._rng.random() < 0.3:
            plans.append({"group": "G99", "action": "REBOOK", "flights": [{"flight_number": "SK503", "departure_date": "2026-11-02"}]})
        return Turn(content=None, tool_calls=[ToolCall(id="answer", name="submit_plan", arguments={"plans": plans})])
