"""The agent over HTTP, for the operations page (ADR-0024).

One endpoint, `POST /v1/proposals`: give it a disrupted flight, get back what to do with each booking on it. It only
proposes. Moving a booking is done by the operations page with the staff member's own login, so the audit names the
person who approved, not this service. The answer holds references and counts only: no names, no emails, no credentials.

Planners are registered by name (`planners`), so another model or strategy is one more entry and nothing here changes.
Work is bounded: at most `max_concurrent` runs at once (each may call a model), the rest wait briefly and are then told
to try again, so a burst of clicks cannot pile up model calls."""
from __future__ import annotations

import asyncio
import hmac
import re
from collections.abc import Callable, Mapping
from datetime import date

from mcp import Client
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from .agent import DisruptionAgent
from .clients import BookingClient, SearchClient, ServiceError
from .llm import ChatModel
from .models import Report
from .server import build_server
from .toolbox import McpToolBox, unwrapped

MIN_TOKEN_LENGTH = 32
FLIGHT = re.compile(r"^[A-Z0-9]{2}[0-9]{1,4}$")


class Busy(Exception):
    """Every place for a run is taken and none freed up in time."""


class RunGate:
    """At most `max_concurrent` runs at once. A caller that cannot get a place within `wait_seconds` is turned away."""

    def __init__(self, max_concurrent: int, wait_seconds: float):
        if max_concurrent < 1 or wait_seconds < 0:
            raise ValueError("max_concurrent must be at least 1 and wait_seconds not negative")
        self._slots = asyncio.Semaphore(max_concurrent)
        self._wait = wait_seconds

    async def __aenter__(self) -> "RunGate":
        try:
            await asyncio.wait_for(self._slots.acquire(), self._wait)
        except TimeoutError:
            raise Busy from None
        return self

    async def __aexit__(self, *exc_info) -> None:
        self._slots.release()


def _problem(status: int, detail: str) -> JSONResponse:
    return JSONResponse({"detail": detail}, status_code=status)


def _report_json(report: Report, planner: str) -> dict:
    return {
        "planner": planner,
        "flightNumber": report.flight_number,
        "departureDate": report.departure_date.isoformat(),
        "bookingsTotal": report.bookings_total,
        "rebooked": report.rebooked,
        "proposals": [{
            "locator": p.locator,
            "action": p.action,
            "flightNumber": p.flight_number,
            "departureDate": p.departure_date.isoformat() if p.departure_date else None,
            "segmentIndex": p.segment_index,
            "passengerCount": p.passenger_count,
            "rationale": p.rationale,
        } for p in report.proposals],
    }


def create_app(booking: BookingClient, search: SearchClient, api_token: str,
               planners: Mapping[str, Callable[[], ChatModel]], default_planner: str,
               max_concurrent: int = 2, wait_seconds: float = 2.0, batch_size: int = 40) -> Starlette:
    if len(api_token) < MIN_TOKEN_LENGTH:
        raise ValueError(f"The agent API token must be at least {MIN_TOKEN_LENGTH} characters")
    if default_planner not in planners:
        raise ValueError(f"The default planner {default_planner!r} is not registered")
    expected = api_token.encode()
    gate = RunGate(max_concurrent, wait_seconds)

    async def proposals(request: Request) -> JSONResponse:
        # Constant-time comparison: the response time must not reveal how much of a guess was right.
        provided = request.headers.get("authorization", "").removeprefix("Bearer ").encode()
        if not hmac.compare_digest(provided, expected):
            return _problem(401, "A valid agent API token is required")
        try:
            body = await request.json()
            flight = str(body["flightNumber"])
            day = date.fromisoformat(str(body["departureDate"]))
        except (ValueError, KeyError, TypeError):
            return _problem(400, "Send JSON with flightNumber (like SK501) and departureDate (YYYY-MM-DD)")
        name = body.get("planner") or default_planner
        if not FLIGHT.match(flight) or name not in planners:
            return _problem(400, f"flightNumber must look like SK501, and planner one of: {', '.join(sorted(planners))}")
        try:
            async with gate:
                return await _run(flight, day, name)
        except Busy:
            return _problem(503, "The agent is busy with other requests. Try again in a moment.")

    async def _run(flight: str, day: date, name: str) -> JSONResponse:
        try:
            planner = planners[name]()
        except Exception:   # a model that cannot start (bad key, library missing) is a gateway problem, not the caller's
            return _problem(502, f"The {name} planner could not start")
        try:
            async with unwrapped(), Client(build_server(booking, search)) as client:
                report = await DisruptionAgent(planner, McpToolBox(client), batch_size=batch_size).run(flight, day)
        except ServiceError as problem:
            return _problem(502, str(problem))
        return JSONResponse(_report_json(report, name), headers={"Cache-Control": "no-store"})

    async def health(request: Request) -> JSONResponse:
        return JSONResponse({"status": "up"})

    app = Starlette(routes=[Route("/v1/proposals", proposals, methods=["POST"]), Route("/healthz", health)])
    app.state.gate = gate
    return app


def main() -> None:   # pragma: no cover - needs the real services
    import logging
    import os

    import uvicorn

    from .config import Settings
    from .firstfit import FirstFitPlanner

    logging.getLogger("httpx").setLevel(logging.WARNING)
    token = os.environ.get("SKYHUB_AGENT_API_TOKEN", "")
    if len(token) < MIN_TOKEN_LENGTH:
        raise SystemExit(f"Set SKYHUB_AGENT_API_TOKEN to at least {MIN_TOKEN_LENGTH} random characters.")
    settings = Settings.from_env()
    planners: dict[str, Callable[[], ChatModel]] = {"first-fit": FirstFitPlanner}
    key = os.environ.get("GROQ_API_KEY")
    if key:
        def groq() -> ChatModel:
            from groq import Groq

            from .llm import GroqChatModel
            return GroqChatModel(Groq(api_key=key), settings.groq_model)
        planners["groq"] = groq
    default = os.environ.get("SKYHUB_AGENT_PLANNER", "first-fit")   # the rule-based planner unless told otherwise
    app = create_app(settings.booking_client(), settings.search_client(), token, planners, default)
    uvicorn.run(app, host=os.environ.get("SKYHUB_AGENT_HOST", "127.0.0.1"), port=int(os.environ.get("SKYHUB_AGENT_PORT", "8084")))


if __name__ == "__main__":   # pragma: no cover
    main()
