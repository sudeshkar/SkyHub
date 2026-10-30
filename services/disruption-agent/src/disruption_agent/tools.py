"""The tools the agent (and any other MCP client) can use. Each tool is a class; the server is built from a registry, so
adding a tool means writing one class and registering it, with no change to the server or to the agent loop."""
from __future__ import annotations

import functools
from abc import ABC, abstractmethod
from collections.abc import Callable
from datetime import date, datetime, timezone

from .cache import TtlCache
from .clients import ServiceError
from .models import Alternative
from .planning import AlternativeFinder, Impact, ImpactAnalyzer

MAX_PAGE = 200            # one answer is never longer than this many bookings
DEFAULT_PAGE = 40
MAX_PASSENGERS = 9        # the most one SkyHub booking can hold


class ToolInputError(ValueError):
    """The caller asked a question that makes no sense. The message is written for a model or a person to read."""


class Tool(ABC):
    """One capability. `run` is declared with typed parameters; MCP reads them to build the tool's input schema."""

    name: str
    description: str

    @abstractmethod
    def run(self, *args, **kwargs) -> dict:  # concrete tools narrow the signature
        ...


def _day(value: str, field: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise ToolInputError(f"{field} must be a date like 2026-11-02") from None


def _utc(value: str, field: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise ToolInputError(f"{field} must be a UTC time like 2026-11-02T08:30:00Z") from None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


class GetDisruptionImpact(Tool):
    name = "get_disruption_impact"
    description = ("The active bookings on one disrupted flight: how many passengers each holds, its route, and the window "
                   "(earliest departure, latest arrival) it can be moved within. No names or contact details. "
                   "Paged: pass the returned next_offset to get the next page.")

    def __init__(self, analyzer: ImpactAnalyzer, cache: TtlCache[tuple[str, date], Impact] | None = None):
        self._analyzer = analyzer
        # paging asks again for the same flight: analyse once, serve every page from memory
        self._cache: TtlCache = cache or TtlCache(ttl_seconds=60, max_entries=8)

    def run(self, flight_number: str, departure_date: str, offset: int = 0, limit: int = DEFAULT_PAGE) -> dict:
        if not 1 <= limit <= MAX_PAGE or offset < 0:
            raise ToolInputError(f"limit must be between 1 and {MAX_PAGE}, and offset 0 or more")
        day = _day(departure_date, "departure_date")
        impact = self._cache.get_or_compute((flight_number, day), lambda: self._analyzer.analyze(flight_number, day))
        page = impact.bookings[offset:offset + limit]
        end = offset + len(page)
        return {
            "flight": impact.flight.model_dump(mode="json"),
            "total_bookings": len(impact.bookings),
            "total_passengers": impact.total_passengers,
            "offset": offset,
            "next_offset": end if end < len(impact.bookings) else None,
            "bookings": [b.model_dump(mode="json") for b in page],
        }


class FindAlternatives(Tool):
    name = "find_alternatives"
    description = ("Direct flights on a route that have enough free seats for a group, in departure order. Use "
                   "not_before (UTC) as the earliest departure and, if the traveller has a flight to catch afterwards, "
                   "latest_arrival (UTC). Only flights returned here may be proposed.")

    def __init__(self, finder: AlternativeFinder):
        self._finder = finder

    def run(self, origin: str, destination: str, not_before: str, passengers: int = 1,
            latest_arrival: str | None = None) -> dict:
        if not 1 <= passengers <= MAX_PASSENGERS:
            raise ToolInputError(f"passengers must be between 1 and {MAX_PASSENGERS}")
        found = self._finder.find(origin.upper(), destination.upper(), not_before=_utc(not_before, "not_before"),
                                  passengers=passengers,
                                  latest_arrival=_utc(latest_arrival, "latest_arrival") if latest_arrival else None)
        return {"alternatives": [_alternative_json(a) for a in found]}


def _alternative_json(alternative: Alternative) -> dict:
    return {**alternative.model_dump(mode="json"), "departure_date": alternative.departure_date.isoformat()}


class ToolRegistry:
    """Holds the tools and installs them on an MCP server. Errors a caller can act on are returned as
    `{"error": "..."}` rather than raised, because MCP hides a raised error behind a generic message and a model can only
    correct itself from a message it can read."""

    def __init__(self, tools: list[Tool]):
        names = [t.name for t in tools]
        if len(set(names)) != len(names):
            raise ValueError("two tools share a name")
        self._tools = tools

    def install(self, server) -> None:
        for tool in self._tools:
            server.add_tool(self._safe(tool.run), name=tool.name, description=tool.description)

    @staticmethod
    def _safe(run: Callable[..., dict]) -> Callable[..., dict]:
        @functools.wraps(run)   # keeps the typed signature MCP builds the schema from
        def guarded(*args, **kwargs) -> dict:
            try:
                return run(*args, **kwargs)
            except (ToolInputError, ServiceError) as problem:
                return {"error": str(problem)}
        return guarded
