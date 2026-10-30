"""Stand-ins for the language model, so the agent's behaviour is tested without any network or API key."""
from __future__ import annotations

import copy
import json
from collections.abc import Callable

from disruption_agent.llm import ToolCall, ToolSpec, Turn


def call(name: str, **arguments) -> Turn:
    return Turn(content=None, tool_calls=[ToolCall(id=f"call-{name}", name=name, arguments=arguments)])


def say(text: str) -> Turn:
    return Turn(content=text, tool_calls=[])


def submit(*proposals: dict) -> Turn:
    return call("submit_proposals", proposals=list(proposals))


def rebook(locator: str, flight: str, day: str = "2026-11-02", why: str = "fits") -> dict:
    return {"locator": locator, "action": "REBOOK", "flight_number": flight, "departure_date": day, "rationale": why}


class ScriptedModel:
    """Plays back a fixed list of turns, and remembers everything it was shown."""

    def __init__(self, *turns: Turn):
        self.turns = list(turns)
        self.seen: list[list[dict]] = []
        self.tools_offered: list[list[ToolSpec]] = []

    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn:
        self.seen.append(copy.deepcopy(messages))
        self.tools_offered.append(tools)
        return self.turns.pop(0) if self.turns else say("I have nothing more to add.")


class FunctionModel:
    """Decides each turn with a function, for scenarios that depend on what the prompt contains."""

    def __init__(self, decide: Callable[[list[dict]], Turn]):
        self.decide = decide
        self.seen: list[list[dict]] = []

    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn:
        self.seen.append(copy.deepcopy(messages))
        return self.decide(messages)


def everything_shown(model) -> str:
    return json.dumps(model.seen, default=str)
