"""skyhub-traveller: the traveller assistant's MCP server (ADR-0092).

Run it with ``python -m traveller_assistant.server``; it speaks MCP over stdin and stdout, so it opens no port, and the
crew starts it as a child process. Any MCP client can use it too (the MCP Inspector, Claude Desktop). It holds no
credentials: its tools are read-only and none can see or change a booking. stdout carries the protocol, so logs go to
stderr.
"""

from __future__ import annotations

import logging
import sys

from mcp.server.fastmcp import FastMCP

from traveller_assistant.flights import FlightSearchClient
from traveller_assistant.index import HelpCentreIndex
from traveller_assistant.tools import SearchFlights, SearchHelpCentre, ToolRegistry

INSTRUCTIONS = ("Tools for SkyHub's travellers, all read-only: the help centre, and flight search. The server holds "
                "no credentials and cannot see or change any booking. Answer questions about SkyHub's rules only from "
                "search_help_centre, and about flights only from search_flights.")


def build_server(index: HelpCentreIndex, flights: FlightSearchClient) -> FastMCP:
    server = FastMCP("skyhub-traveller", instructions=INSTRUCTIONS, log_level="WARNING")
    ToolRegistry([SearchHelpCentre(index), SearchFlights(flights)]).install(server)
    return server


def main() -> None:   # pragma: no cover - started as a process by tests/test_stdio.py
    from traveller_assistant.config import Settings
    from traveller_assistant.embedding import MiniLmEmbedder
    from traveller_assistant.index import build_index

    logging.basicConfig(stream=sys.stderr, level=logging.WARNING)
    settings = Settings.from_env()
    build_server(build_index(MiniLmEmbedder()), settings.flight_search()).run("stdio")


if __name__ == "__main__":   # pragma: no cover
    main()
