"""`python -m disruption_agent SK501 2026-11-02`: prints proposals for a person to review. Changes nothing in SkyHub."""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from datetime import date

from mcp import Client, StdioServerParameters

from .agent import DisruptionAgent
from .apply import Applier, ConsoleApprover, summary
from .config import Settings
from .llm import GroqChatModel
from .clients import ServiceError
from .toolbox import McpToolBox, unwrapped


async def propose(flight_number: str, day: date, settings: Settings, api_key: str):
    from groq import Groq

    # The MCP server runs as its own process and holds the staff credentials; the agent only ever sees tool results.
    server = StdioServerParameters(command=sys.executable, args=["-m", "disruption_agent.server"],
                                   env={**os.environ, "PYTHONUTF8": "1"})
    async with unwrapped(), Client(server) as client:
        model = GroqChatModel(Groq(api_key=api_key), settings.groq_model)
        return await DisruptionAgent(model, McpToolBox(client)).run(flight_number, day)


def main(argv: list[str] | None = None) -> int:
    logging.getLogger("httpx").setLevel(logging.WARNING)   # one INFO line per request is noise for a person
    parser = argparse.ArgumentParser(prog="disruption_agent", description=__doc__)
    parser.add_argument("flight_number", help="the disrupted flight, e.g. SK501")
    parser.add_argument("departure_date", type=date.fromisoformat, help="its scheduled UTC departure date, YYYY-MM-DD")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    parser.add_argument("--apply", action="store_true",
                        help="after the report, ask about each proposed move and carry out the ones you approve (needs a terminal)")
    args = parser.parse_args(argv)

    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        print("Set GROQ_API_KEY (a free key from console.groq.com).", file=sys.stderr)
        return 2
    try:
        report = asyncio.run(propose(args.flight_number.upper(), args.departure_date, Settings.from_env(), api_key))
    except ServiceError as problem:
        print(f"Could not complete: {problem}", file=sys.stderr)
        return 1
    print(report.model_dump_json(indent=2) if args.json else report.to_text())
    if args.apply:
        return _apply(report)
    return 0


def _apply(report) -> int:
    """Moves what a person approves, one booking at a time. Never unattended: there is deliberately no --yes."""
    if not sys.stdin.isatty():
        print("--apply asks you about each move, so it needs a terminal. Nothing was changed.", file=sys.stderr)
        return 2
    outcomes = Applier(Settings.from_env().booking_client(), ConsoleApprover()).apply(report)
    print(summary(outcomes))
    return 1 if any(o.status == "failed" for o in outcomes) else 0
