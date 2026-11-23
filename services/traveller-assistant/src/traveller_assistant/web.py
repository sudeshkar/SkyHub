"""The traveller assistant over HTTP (ADR-0093), for the website's server only.

``POST /api/v1/assistant/messages``: a message and the turns before it, an answer back. Contract:
``api-specs/traveller-assistant-api.yaml``. Only a caller with the shared token gets an answer (the website keeps the
token on its server, never in a browser). At most ``max_concurrent`` answers are worked on at once, each in a thread,
because a CrewAI crew blocks; a further caller waits ``wait_seconds`` and is then told to try again. Nothing about a
conversation is kept here.

Run it with ``python -m traveller_assistant.web``: it starts the traveller MCP server once, over stdio, and stops it
on the way out.
"""

from __future__ import annotations

import asyncio
import contextlib
import hmac
import logging
import time
from collections.abc import AsyncIterator, Callable
from typing import Protocol

import anyio
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Counter, Histogram, generate_latest
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route as Path

from traveller_assistant.assistant import Answer, Conversation, InvalidMessage, ModelUnavailable

MIN_TOKEN_LENGTH = 32
RETRY_AFTER_SECONDS = 30

log = logging.getLogger(__name__)


class Assistant(Protocol):
    def answer(self, conversation: Conversation) -> Answer: ...


class Busy(Exception):
    """Every place for an answer is taken and none freed up in time."""


class RunGate:
    """At most ``max_concurrent`` answers at once; a caller that cannot get a place within ``wait_seconds`` is turned
    away. The same rule as the disruption agent's (ADR-0024): a burst of messages cannot pile up model calls."""

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


def _problem(status: int, detail: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"detail": detail}, status_code=status, headers=headers)


def _answer_json(answer: Answer) -> dict:
    return {"reply": answer.reply, "route": answer.route.value, "outcome": answer.outcome,
            "sources": [{"article": s.article, "title": s.title} for s in answer.sources]}


def create_app(assistant: Assistant, api_token: str, *, registry: CollectorRegistry, max_concurrent: int = 2,
               wait_seconds: float = 2.0,
               lifespan: Callable[[Starlette], contextlib.AbstractAsyncContextManager] | None = None) -> Starlette:
    if len(api_token) < MIN_TOKEN_LENGTH:
        raise ValueError(f"The assistant API token must be at least {MIN_TOKEN_LENGTH} characters")
    expected = api_token.encode()
    gate = RunGate(max_concurrent, wait_seconds)
    messages = Counter("skyhub_assistant_messages", "Messages answered, by route and outcome",
                       ["route", "outcome"], registry=registry)
    seconds = Histogram("skyhub_assistant_message_seconds", "Time to answer a message, model included",
                        registry=registry, buckets=(0.5, 1, 2, 4, 8, 15, 30, 60))

    def counted(response: Response, route: str, outcome: str) -> Response:
        messages.labels(route=route, outcome=outcome).inc()
        return response

    async def answer(request: Request) -> Response:
        # Constant-time comparison: the response time must not reveal how much of a guess was right.
        provided = request.headers.get("authorization", "").removeprefix("Bearer ").encode()
        if not request.headers.get("authorization", "").startswith("Bearer ") or not hmac.compare_digest(provided, expected):
            return counted(_problem(401, "A valid assistant API token is required"), "none", "unauthorised")
        try:
            conversation = Conversation.from_request(await request.json())
        except InvalidMessage as problem:
            return counted(_problem(400, str(problem)), "none", "invalid")
        except ValueError:
            return counted(_problem(400, "Send JSON with a message and, if any, the history."), "none", "invalid")

        started = time.perf_counter()
        try:
            async with gate:
                result = await anyio.to_thread.run_sync(assistant.answer, conversation)
        except Busy:
            return counted(_problem(503, "The assistant is busy with other travellers. Please try again in a moment.",
                                    {"Retry-After": "5"}), "none", "busy")
        except ModelUnavailable as problem:
            log.warning("The model could not answer: %s", problem)
            return counted(_problem(503, "The assistant can't answer right now. Please try again in a minute.",
                                    {"Retry-After": str(RETRY_AFTER_SECONDS)}), "none", "unavailable")
        except Exception:
            log.exception("The assistant failed")
            return counted(_problem(500, "The assistant ran into a problem. Please try again."), "none", "error")
        finally:
            seconds.observe(time.perf_counter() - started)
        return counted(JSONResponse(_answer_json(result), headers={"Cache-Control": "no-store"}),
                       result.route.value, result.outcome)

    async def health(request: Request) -> JSONResponse:
        return JSONResponse({"status": "up"})

    async def metrics(request: Request) -> Response:
        return Response(generate_latest(registry), media_type=CONTENT_TYPE_LATEST)

    return Starlette(routes=[Path("/api/v1/assistant/messages", answer, methods=["POST"]), Path("/healthz", health),
                             Path("/metrics", metrics)], lifespan=lifespan)


def mcp_server_parameters(environ: dict[str, str]):
    """How to start the traveller MCP server: this Python, and only the environment it needs.

    ADR-0092's server holds no credentials, so neither the model's key nor this API's token is passed to it: only what
    any process needs (from the MCP SDK's safe list, which includes the home directory where the model is cached) and
    where flight-search is.
    """
    import sys

    from mcp import StdioServerParameters
    from mcp.client.stdio import get_default_environment

    env = get_default_environment()
    if "SKYHUB_SEARCH_API_URL" in environ:
        env["SKYHUB_SEARCH_API_URL"] = environ["SKYHUB_SEARCH_API_URL"]
    return StdioServerParameters(command=sys.executable, args=["-m", "traveller_assistant.server"], env=env)


def main() -> None:   # pragma: no cover - needs a Groq key; the evals run the same pieces
    import os

    import uvicorn

    from traveller_assistant.assistant import TravellerAssistant
    from traveller_assistant.crew import DEFAULT_MODEL, CrewAiCrews, LlmRouter, groq_llm

    logging.basicConfig(level=logging.INFO)
    token = os.environ.get("SKYHUB_ASSISTANT_API_TOKEN", "")
    if len(token) < MIN_TOKEN_LENGTH:
        raise SystemExit(f"Set SKYHUB_ASSISTANT_API_TOKEN to at least {MIN_TOKEN_LENGTH} random characters.")
    key = os.environ.get("GROQ_API_KEY", "")
    if not key:
        raise SystemExit("Set GROQ_API_KEY: the assistant needs a model (a free Groq key is enough).")
    llm = groq_llm(key, os.environ.get("GROQ_MODEL", DEFAULT_MODEL), max_retries=1)
    server = mcp_server_parameters(dict(os.environ))
    holder: dict[str, Assistant] = {}

    class Started:
        """The assistant, once the MCP server is running."""

        def answer(self, conversation: Conversation) -> Answer:
            return holder["assistant"].answer(conversation)

    @contextlib.asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
        from crewai_tools import MCPServerAdapter

        adapter = await anyio.to_thread.run_sync(lambda: MCPServerAdapter(server, connect_timeout=120))
        try:
            holder["assistant"] = TravellerAssistant(LlmRouter(llm), CrewAiCrews(llm, list(adapter.tools)))
            yield
        finally:
            await anyio.to_thread.run_sync(adapter.stop)

    app = create_app(Started(), token, registry=CollectorRegistry(), lifespan=lifespan)
    uvicorn.run(app, host=os.environ.get("SKYHUB_ASSISTANT_HOST", "127.0.0.1"),
                port=int(os.environ.get("SKYHUB_ASSISTANT_PORT", "8085")))


if __name__ == "__main__":   # pragma: no cover
    main()
