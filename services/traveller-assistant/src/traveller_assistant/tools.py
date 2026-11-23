"""The tools the assistant's model can call (ADR-0091, ADR-0092).

Each tool is a class with a typed ``run``; the MCP server is built from a registry of them, so adding a tool is one class
and one line, with no change to the server. Every tool here is read-only: none can see or change a booking.
"""

from __future__ import annotations

import functools
import inspect
import re
from abc import ABC, abstractmethod
from collections.abc import Callable
from datetime import date
from typing import Any

from mcp.types import ToolAnnotations

from traveller_assistant.errors import ServiceError, ToolInputError
from traveller_assistant.flights import FlightSearchClient
from traveller_assistant.index import HelpCentreIndex

__all__ = ["ToolInputError", "ServiceError", "Tool", "SearchHelpCentre", "SearchFlights", "ToolRegistry",
           "search_help_centre"]

MAX_QUESTION_CHARACTERS = 500
MAX_PASSAGES = 5
DEFAULT_PASSAGES = 3
MAX_ITINERARIES = 10

NOTHING_FOUND = ("The help centre has nothing that answers this. Tell the traveller you do not know, suggest the help "
                 "centre or the Manage booking page, and do not guess.")

# Passages near a question do not always answer it: "how much baggage can I bring?" finds the booking article, which
# says nothing about baggage (ADR-0091). So every answer with passages carries the rule.
ONLY_FROM_THESE = ("Answer only from these passages and name the article you used. If they do not answer the question, "
                   "tell the traveller you do not know and do not guess.")

ABOUT_FLIGHTS = ("Times are UTC. fare_from is the lowest price per passenger, not a quote: seats and the final price are "
                 "shown when booking on SkyHub. A cancelled flight is never listed. Only mention flights listed here.")
NO_FLIGHTS = ("SkyHub has no flights on this route on that day. Suggest another day, or check the airport codes; "
              "do not suggest other airlines.")

_AIRPORT = re.compile(r"[A-Za-z]{3}")
READ_ONLY = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True)


class Tool(ABC):
    """One capability. ``run`` is declared with typed parameters; MCP reads them to build the tool's input schema."""

    name: str
    description: str
    annotations: ToolAnnotations = READ_ONLY

    @abstractmethod
    def run(self, *args, **kwargs) -> dict:  # concrete tools narrow the signature
        ...


def search_help_centre(index: HelpCentreIndex, question: str, limit: int = DEFAULT_PASSAGES) -> dict:
    """Passages of the help centre that answer ``question``, each naming the article it comes from.

    Bounded work whatever the caller sends: one embedding of at most 500 characters, one query of at most 5 results.
    """
    question = question.strip()
    if not question:
        raise ToolInputError("Ask a question: the question was empty.")
    if len(question) > MAX_QUESTION_CHARACTERS:
        raise ToolInputError(f"Ask a shorter question: at most {MAX_QUESTION_CHARACTERS} characters.")
    if not 1 <= limit <= MAX_PASSAGES:
        raise ToolInputError(f"Ask for between 1 and {MAX_PASSAGES} passages.")

    passages = [{"article": f.article_id, "title": f.title, "section": f.heading, "text": f.text}
                for f in index.search(question, limit)]
    return {"passages": passages, "note": ONLY_FROM_THESE if passages else NOTHING_FOUND}


class SearchHelpCentre(Tool):
    name = "search_help_centre"
    description = ("Search SkyHub's help centre: booking, seats, connections in Dubai, managing a booking, cancellations "
                   "and refunds, check-in, boarding, delays and cancelled flights, accounts. Returns the passages that "
                   "answer the question, each naming its article. Use it for every question about SkyHub's rules.")

    def __init__(self, index: HelpCentreIndex):
        self._index = index

    def run(self, question: str, limit: int = DEFAULT_PASSAGES) -> dict:
        return search_help_centre(self._index, question, limit)


class SearchFlights(Tool):
    name = "search_flights"
    description = ("SkyHub's flights on one day between two airports: direct flights and connections in Dubai, earliest "
                   "first, with times (UTC), status and the lowest fare per passenger. Airports are three-letter codes "
                   "(DXB, LHR); the date is YYYY-MM-DD. At most 10 are returned.")

    def __init__(self, flights: FlightSearchClient):
        self._flights = flights

    def run(self, origin: str, destination: str, date: str) -> dict:
        origin_code = _airport(origin, "origin")
        destination_code = _airport(destination, "destination")
        if origin_code == destination_code:
            raise ToolInputError("origin and destination must be different airports")
        day = _day(date)

        found = self._flights.itineraries(origin_code, destination_code, day)
        if not found:
            return {"itineraries": [], "total": 0, "note": NO_FLIGHTS}
        return {"itineraries": [_itinerary(i) for i in found[:MAX_ITINERARIES]], "total": len(found),
                "note": ABOUT_FLIGHTS}


def _airport(value: str, field: str) -> str:
    if not _AIRPORT.fullmatch(value.strip()):
        raise ToolInputError(f"{field} must be a three-letter airport code, like DXB")
    return value.strip().upper()


def _day(value: str) -> date:
    try:
        return date.fromisoformat(value.strip())
    except ValueError:
        raise ToolInputError("date must be a date like 2026-12-01") from None


def _itinerary(itinerary: dict) -> dict:
    """Only what a traveller needs, in plain names: no aircraft, no per-leg fare."""
    connections = itinerary.get("connections") or []
    return {
        "flights": [_flight(leg) for leg in itinerary["legs"]],
        "stops": itinerary["stops"],
        "connection": _connection(connections[0]) if connections else None,
        "duration_minutes": itinerary["totalDurationMinutes"],
        "fare_from": itinerary["fareFrom"],
        "currency": itinerary["currency"],
    }


def _flight(leg: dict) -> dict:
    flight = {"flight": leg["flightNumber"], "from": leg["origin"], "to": leg["destination"],
              "departs": leg["departureTime"], "arrives": leg["arrivalTime"], "status": leg.get("status") or "SCHEDULED"}
    if leg.get("revisedDeparture"):
        flight["now_departs"] = leg["revisedDeparture"]
    return flight


def _connection(connection: dict) -> dict:
    shown = {"at": connection["arrivalAirport"], "leaves_from": connection["departureAirport"],
             "layover_minutes": connection["layoverMinutes"], "changes_airport": connection["requiresAirportTransfer"]}
    if connection["requiresAirportTransfer"]:
        shown["transfer_minutes"] = connection["transferMinutes"]
    return shown


class ToolRegistry:
    """Holds the tools and installs them on an MCP server. Errors a caller can act on are returned as
    ``{"error": "..."}`` rather than raised, because MCP hides a raised error behind a generic message and a model can
    only correct itself, or tell the traveller, from a message it can read."""

    def __init__(self, tools: list[Tool]):
        names = [t.name for t in tools]
        if len(set(names)) != len(names):
            raise ValueError("two tools share a name")
        self._tools = tools

    def install(self, server) -> None:
        for tool in self._tools:
            server.add_tool(self._safe(tool.run), name=tool.name, description=tool.description,
                            annotations=tool.annotations, structured_output=True)

    @staticmethod
    def _safe(run: Callable[..., dict]) -> Callable[..., dict]:
        @functools.wraps(run)   # keeps the typed parameters MCP builds the input schema from
        def guarded(*args, **kwargs) -> dict[str, Any]:
            try:
                return run(*args, **kwargs)
            except (ToolInputError, ServiceError) as problem:
                return {"error": str(problem)}
        # MCP sends structured content only for a declared dict[str, Any]; a bare dict would arrive as text only.
        guarded.__signature__ = inspect.signature(run).replace(return_annotation=dict[str, Any])
        return guarded
