"""A model that says what it is told to, so CrewAI runs for real in tests with no key and no network.

It does not use native tool calling, so CrewAI drives it in its text format: a reply ``Action: <tool>`` with
``Action Input: {...}`` makes CrewAI run that tool, and ``Final Answer: ...`` ends the task.
"""

from __future__ import annotations

import json
from typing import Any

from crewai import BaseLLM
from crewai.tools import BaseTool
from pydantic import BaseModel, Field


class ScriptedLLM(BaseLLM):
    replies: list[Any] = Field(default_factory=list)
    seen: list[Any] = Field(default_factory=list)

    def call(self, messages, tools=None, callbacks=None, available_functions=None, from_task=None, from_agent=None,
             response_model=None):
        self.seen.append(messages)
        reply = self.replies.pop(0)
        if isinstance(reply, BaseException):
            raise reply
        return reply

    def supports_function_calling(self) -> bool:
        return False

    def prompt_text(self, call: int = 0) -> str:
        messages = self.seen[call]
        return messages if isinstance(messages, str) else "\n".join(str(m.get("content", "")) for m in messages)


def act(tool: str, **arguments) -> str:
    return f"Thought: I need {tool}.\nAction: {tool}\nAction Input: {json.dumps(arguments)}"


def final(text: str) -> str:
    return f"Thought: I can answer now.\nFinal Answer: {text}"


class QuestionArgs(BaseModel):
    question: str
    limit: int = 3


class FlightArgs(BaseModel):
    origin: str
    destination: str
    date: str


class FakeTool(BaseTool):
    """Stands in for one of the MCP server's tools: answers with fixed JSON text, as the MCP adapter's tools do."""

    name: str
    description: str = "A tool."
    args_schema: type[BaseModel] = QuestionArgs
    answer: dict = Field(default_factory=dict)
    received: list[dict] = Field(default_factory=list)

    def _run(self, **kwargs: Any) -> str:
        self.received.append(kwargs)
        return json.dumps(self.answer)


class BookingArgs(BaseModel):
    reference: str
    booking_token: str


MY_BOOKING = {"status": "CONFIRMED", "passengers": 2,
              "flights": [{"flight": "SK201", "checked_in": ["passenger 1"]}, {"flight": "SK701", "checked_in": []}],
              "note": "Passengers are not named."}

CHECK_IN = {"passages": [{"article": "checking-in-online", "title": "Checking in online", "section": "When",
                          "text": "Online check-in opens 24 hours before departure."}], "note": "Answer only from these."}
BOM_JFK = {"itineraries": [{"flights": [{"flight": "SK201"}, {"flight": "SK701"}]}], "total": 1, "note": "UTC."}


def tools() -> dict[str, FakeTool]:
    return {"search_help_centre": FakeTool(name="search_help_centre", description="Search the help centre.",
                                           answer=CHECK_IN),
            "search_flights": FakeTool(name="search_flights", description="Search flights.", args_schema=FlightArgs,
                                       answer=BOM_JFK),
            "get_my_booking": FakeTool(name="get_my_booking", description="The traveller's own booking.",
                                       args_schema=BookingArgs, answer=MY_BOOKING)}
