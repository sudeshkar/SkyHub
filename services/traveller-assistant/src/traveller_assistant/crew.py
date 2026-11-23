"""The CrewAI side of the traveller assistant (ADR-0093): the router, the two agents and their tasks.

A small router call picks the kind of question; a one-agent crew answers it: the policy agent with only the help centre,
the trip agent with flight search and the help centre. Agents and tasks are built for each message, so two travellers
never share state. Each tool is wrapped for the message, so the code that checks the answer knows what it returned.

CrewAI's usage telemetry is switched off before CrewAI is imported.
"""

from __future__ import annotations

import os

for _flag in ("CREWAI_DISABLE_TELEMETRY", "CREWAI_DISABLE_TRACKING", "OTEL_SDK_DISABLED"):
    os.environ[_flag] = "true"
os.environ["CREWAI_TRACING_ENABLED"] = "false"

import contextlib   # noqa: E402  (after the telemetry switches, on purpose)
import json   # noqa: E402
import logging   # noqa: E402
from collections.abc import Callable, Iterator, Sequence   # noqa: E402
from datetime import date, datetime, timezone   # noqa: E402
from typing import Any   # noqa: E402

import openai   # noqa: E402
from crewai import Agent, BaseLLM, Crew, Process, Task   # noqa: E402
from crewai.tools import BaseTool   # noqa: E402
from pydantic import BaseModel, PrivateAttr   # noqa: E402

from traveller_assistant.assistant import Conversation, ModelUnavailable, Route, ToolLedger, Turn   # noqa: E402

# CrewAI warns on every call that Groq gives no token count; it is noise in the service's log.
logging.getLogger("crewai.llms.base_llm").setLevel(logging.ERROR)

GROQ_URL = "https://api.groq.com/openai/v1"
# The one place the default model is named, as in the disruption agent (ADR-0080): GROQ_MODEL changes it.
DEFAULT_MODEL = "openai/gpt-oss-120b"
MAX_STEPS = 4
ROUTER_TURNS = 4

POLICY_TOOLS = ("search_help_centre",)
TRIP_TOOLS = ("search_flights", "search_help_centre")

ROUTER_PROMPT = """You sort messages sent to SkyHub's travel assistant. SkyHub is an airline with two hubs in Dubai.
Answer with exactly one word:
help - a question about SkyHub's rules or how to do something: booking, seats, cabins, connections, managing or \
cancelling a booking, refunds, check-in, boarding passes, boarding, delays or cancelled flights, accounts and passwords, \
baggage, or anything else a traveller might ask an airline
flights - the traveller wants to find SkyHub flights, times or fares between places or on a date
other - not about travel or SkyHub at all (poems, sums, general knowledge, other companies), or only a greeting"""

RULES = """Rules:
- Use your tools before answering. Answer only from what they return, never from memory or other airlines' rules.
- Never invent a rule, a number, a flight, a time or a price. If the tools do not give the answer, say you do not know.
- Write plain words for a traveller, at most four sentences. Never ask for a card number or a password.
- The last line must be SOURCES: followed by the ids of the help-centre articles you used, separated by commas, \
or SOURCES: none."""

FLIGHT_RULES = """- Name every flight by its flight number (SK201), with its airports and times in UTC, and say fares are \
'from' prices."""

EXPECTED = "A short answer for the traveller, then a last line that starts with SOURCES:"


def groq_llm(api_key: str, model: str = DEFAULT_MODEL, max_retries: int = 2) -> BaseLLM:
    """Groq through CrewAI's OpenAI provider. CrewAI's generic LLM would strip the ``openai/`` of Groq's model name."""
    from crewai.llms.providers.openai.completion import OpenAICompletion

    return OpenAICompletion(model=model, base_url=GROQ_URL, api_key=api_key, temperature=0, max_retries=max_retries,
                            timeout=30)


@contextlib.contextmanager
def _model_errors() -> Iterator[None]:
    """The model's API failing (rate limit, outage, refused key) is the caller's 503, not a crash."""
    try:
        yield
    except openai.APIError as problem:
        raise ModelUnavailable(type(problem).__name__) from problem


def _transcript(turns: Sequence[Turn]) -> str:
    return "\n".join(f"{'Traveller' if t.role == 'traveller' else 'Assistant'}: {t.text}" for t in turns)


class LlmRouter:
    def __init__(self, llm: BaseLLM):
        self._llm = llm

    def route(self, conversation: Conversation) -> Route:
        earlier = _transcript(conversation.history[-ROUTER_TURNS:])
        message = (f"Conversation so far:\n{earlier}\n\n" if earlier else "") + f"Message: {conversation.message}"
        with _model_errors():
            said = self._llm.call([{"role": "system", "content": ROUTER_PROMPT}, {"role": "user", "content": message}])
        words = str(said or "").strip().split()
        first = words[0].strip(".,:;!-*").lower() if words else ""
        return {"flights": Route.FLIGHTS, "other": Route.OTHER}.get(first, Route.HELP)


class RecordingTool(BaseTool):
    """One of the MCP server's tools, for one message: it answers as the tool does and tells the ledger what it said."""

    _inner: BaseTool = PrivateAttr()
    _ledger: ToolLedger = PrivateAttr()

    @classmethod
    def around(cls, inner: BaseTool, ledger: ToolLedger) -> "RecordingTool":
        tool = cls(name=inner.name, description=inner.description, args_schema=inner.args_schema)
        tool._inner = inner
        tool._ledger = ledger
        return tool

    def _run(self, **kwargs: Any) -> Any:
        result = self._inner.run(**kwargs)
        self._ledger.record(self.name, result if isinstance(result, str) else json.dumps(result))
        return result


def utc_today() -> date:
    return datetime.now(timezone.utc).date()


class CrewAiCrews:
    def __init__(self, llm: BaseLLM, tools: Sequence[BaseTool], today: Callable[[], date] = utc_today):
        self._llm = llm
        self._tools = {t.name: t for t in tools}
        self._today = today
        missing = [n for n in set(POLICY_TOOLS + TRIP_TOOLS) if n not in self._tools]
        if missing:
            raise ValueError(f"The MCP server did not offer {', '.join(sorted(missing))}")

    def run(self, route: Route, conversation: Conversation, ledger: ToolLedger) -> str:
        names = TRIP_TOOLS if route is Route.FLIGHTS else POLICY_TOOLS
        agent = (_trip_agent if route is Route.FLIGHTS else _policy_agent)(
            self._llm, [RecordingTool.around(self._tools[n], ledger) for n in names])
        task = Task(description=self._task(conversation, route), expected_output=EXPECTED, agent=agent)
        with _model_errors():
            output = Crew(agents=[agent], tasks=[task], process=Process.sequential, verbose=False).kickoff()
        return str(output.raw)

    def _task(self, conversation: Conversation, route: Route) -> str:
        earlier = _transcript(conversation.history)
        rules = RULES + ("\n" + FLIGHT_RULES if route is Route.FLIGHTS else "")
        return "\n\n".join(part for part in (
            f"Today is {self._today().isoformat()} (UTC).",
            f"Conversation so far:\n{earlier}" if earlier else "",
            f"The traveller now says: {conversation.message}",
            rules) if part)


def _policy_agent(llm: BaseLLM, tools: list[BaseTool]) -> Agent:
    return Agent(
        role="SkyHub help desk",
        goal="Answer a traveller's question about SkyHub's rules from SkyHub's help centre, and only from it",
        backstory=("You work at SkyHub, a demonstration airline with two hubs in Dubai (DXB and DWC). You answer from the "
                   "help centre's passages, never from memory. If the passages do not answer the question, you say you "
                   "do not know. You cannot see or change anyone's booking: changes are made on the Manage booking page."),
        tools=tools, llm=llm, allow_delegation=False, max_iter=MAX_STEPS, verbose=False)


def _trip_agent(llm: BaseLLM, tools: list[BaseTool]) -> Agent:
    return Agent(
        role="SkyHub flight finder",
        goal="Find SkyHub flights for a traveller with the flight search, and answer questions about them",
        backstory=("You work at SkyHub, a demonstration airline with two hubs in Dubai (DXB and DWC). You only mention "
                   "flights that search_flights returned, give their times in UTC and their fares as 'from' prices. You "
                   "know airport codes (Mumbai is BOM). You cannot book, hold seats or see bookings: travellers book on "
                   "the SkyHub website."),
        tools=tools, llm=llm, allow_delegation=False, max_iter=MAX_STEPS, verbose=False)


__all__ = ["CrewAiCrews", "LlmRouter", "RecordingTool", "groq_llm", "BaseModel"]
