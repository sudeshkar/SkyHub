"""The traveller assistant: route a message, let a crew answer it, and check the answer in code (ADR-0093).

The model proposes, code checks (the rule of ADR-0022). Every tool call of a message is recorded in a ``ToolLedger``;
an answer is shown only if what it cites is in the ledger:

* an article in its ``SOURCES`` line must be one ``search_help_centre`` returned in this run;
* a help answer needs at least one such article;
* every flight number it names must be one ``search_flights`` returned in this run.

Otherwise the traveller gets a standard reply. Nothing here knows about CrewAI or the model: the router and the crews
are ports (``Router``, ``Crews``), so the rules are tested on their own.
"""

from __future__ import annotations

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

MAX_MESSAGE = 1000
MAX_TURNS = 6
MAX_TURN = 1000

OTHER_REPLY = ("I can only help with SkyHub: booking and seats, connections in Dubai, managing a booking, cancellations "
               "and refunds, check-in, boarding, delays and cancelled flights, your account, and finding SkyHub flights. "
               "What would you like to know?")
HELP_FALLBACK = ("I don't know the answer to that, and I won't guess: SkyHub's help centre doesn't cover it. For anything "
                 "about your own booking, use the Manage booking page.")
FLIGHTS_FALLBACK = ("I couldn't check SkyHub's flights for that just now. Please try the flight search on the SkyHub "
                    "website.")

# Two letters and up to four digits: SK501. An aircraft (A350) or a time (08:30) is not one.
_FLIGHT = re.compile(r"\b[A-Z]{2}\d{1,4}\b")
# The marker, wherever the model puts it: on a line of its own or at the end of the answer, in bold or in code.
_SOURCES = re.compile(r"[*_`]*\bsources\b[*_`\s]*:[*_`]*", re.IGNORECASE)
# Models write no-break and narrow spaces ("75\u202fminutes"); read them as plain spaces.
_ODD_SPACES = str.maketrans({c: " " for c in "\u00a0\u2007\u2009\u202f"})
_ARTICLE_ID = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")


class Route(StrEnum):
    HELP = "help"
    FLIGHTS = "flights"
    OTHER = "other"


class InvalidMessage(ValueError):
    """A request the API refuses (400); the message says what to send instead."""


class ModelUnavailable(Exception):
    """The model could not answer (rate limit, outage, bad key): the API says to try again (503)."""


@dataclass(frozen=True)
class Turn:
    role: str
    text: str


@dataclass(frozen=True)
class Conversation:
    message: str
    history: tuple[Turn, ...] = ()

    @classmethod
    def from_request(cls, body: object) -> "Conversation":
        if not isinstance(body, Mapping):
            raise InvalidMessage("Send a JSON object with a message and, if any, the history.")
        unknown = sorted(set(body) - {"message", "history"})
        if unknown:
            raise InvalidMessage(f"unknown field '{unknown[0]}'")
        message = body.get("message")
        if not isinstance(message, str) or not message.strip() or len(message.strip()) > MAX_MESSAGE:
            raise InvalidMessage(f"message must be text of 1 to {MAX_MESSAGE} characters")
        history = body.get("history", [])
        if not isinstance(history, list):
            raise InvalidMessage("history must be a list of turns")
        if len(history) > MAX_TURNS:
            raise InvalidMessage(f"history may hold at most {MAX_TURNS} earlier turns")
        return cls(message.strip(), tuple(_turn(t) for t in history))


def _turn(turn: object) -> Turn:
    if not isinstance(turn, Mapping) or set(turn) != {"role", "text"} or turn["role"] not in ("traveller", "assistant"):
        raise InvalidMessage("each turn needs a role of traveller or assistant, and text")
    text = turn["text"]
    if not isinstance(text, str) or not text.strip() or len(text) > MAX_TURN:
        raise InvalidMessage(f"each turn needs text of 1 to {MAX_TURN} characters")
    return Turn(turn["role"], text.strip())


@dataclass(frozen=True)
class Source:
    article: str
    title: str


@dataclass(frozen=True)
class Answer:
    reply: str
    route: Route
    outcome: str
    """answered, declined, unverified or other (see the contract)."""
    sources: tuple[Source, ...] = ()


class ToolLedger:
    """What the tools returned during one message. Errors and anything that is not the tools' JSON add nothing."""

    def __init__(self) -> None:
        self.articles: dict[str, str] = {}
        self.flights: set[str] = set()
        self.calls: list[str] = []

    def record(self, tool: str, result: str) -> None:
        self.calls.append(tool)
        try:
            data = json.loads(result)
        except (TypeError, ValueError):
            return
        if not isinstance(data, dict):
            return
        if tool == "search_help_centre":
            for passage in data.get("passages") or []:
                self.articles.setdefault(str(passage["article"]), str(passage["title"]))
        elif tool == "search_flights":
            for itinerary in data.get("itineraries") or []:
                self.flights.update(str(f["flight"]) for f in itinerary.get("flights") or [])


class Router(Protocol):
    def route(self, conversation: Conversation) -> Route: ...


class Crews(Protocol):
    def run(self, route: Route, conversation: Conversation, ledger: ToolLedger) -> str:
        """The model's final text. The crew's tools record what they return in ``ledger``."""
        ...


class TravellerAssistant:
    def __init__(self, router: Router, crews: Crews):
        self._router = router
        self._crews = crews

    def answer(self, conversation: Conversation) -> Answer:
        route = self._router.route(conversation)
        if route is Route.OTHER:
            return Answer(OTHER_REPLY, route, "other")
        ledger = ToolLedger()
        return check(route, self._crews.run(route, conversation, ledger), ledger)


def check(route: Route, text: str, ledger: ToolLedger) -> Answer:
    """The model's text as the traveller sees it, or the standard reply if it cites anything the tools did not return."""
    reply, declared = _split_sources(text)
    fallback = FLIGHTS_FALLBACK if route is Route.FLIGHTS else HELP_FALLBACK

    if set(_FLIGHT.findall(reply)) - ledger.flights:
        return Answer(fallback, route, "unverified")
    if declared is None:
        # No SOURCES line: the format was not followed, so nothing says where a rule came from.
        return Answer(reply, route, "answered") if route is Route.FLIGHTS else Answer(fallback, route, "unverified")
    if any(article not in ledger.articles for article in declared):
        return Answer(fallback, route, "unverified")
    sources = tuple(Source(a, ledger.articles[a]) for a in declared)
    if route is Route.HELP and not sources:
        return Answer(fallback, route, "declined")
    return Answer(reply, route, "answered", sources)


def _split_sources(text: str) -> tuple[str, list[str] | None]:
    """The reply without its SOURCES marker, and the article ids after the last marker (None if there is none).

    The ids are what follows the marker on its line; the reply is everything else. Found in the first live run: the
    model put the marker at the end of the answer's own line, after a narrow no-break space.
    """
    text = text.translate(_ODD_SPACES).strip()
    markers = list(_SOURCES.finditer(text))
    if not markers:
        return text, None
    marker = markers[-1]
    line_end = text.find("\n", marker.end())
    line_end = len(text) if line_end == -1 else line_end
    ids = [i for i in _ARTICLE_ID.findall(text[marker.end():line_end].lower()) if i != "none"]
    before = text[:marker.start()].rstrip(" \t*_`>#-").rstrip()
    reply = (before + "\n" + text[line_end:].strip()).strip()
    return reply, list(dict.fromkeys(ids))
