"""The MCP server: SkyHub's disruption tools, usable by this agent or by any MCP client (Claude Desktop, an IDE...).

Run it on its own with `python -m disruption_agent.server`; it speaks MCP over stdin/stdout and reads its settings from
the environment (see config.py). The staff token stays inside this process: a model only ever sees tool results."""
from __future__ import annotations

from mcp.server.mcpserver import MCPServer

from .clients import BookingClient, SearchClient
from .planning import AlternativeFinder, ImpactAnalyzer
from .tools import FindAlternatives, GetDisruptionImpact, ToolRegistry


def build_server(booking: BookingClient, search: SearchClient) -> MCPServer:
    server = MCPServer("skyhub-disruptions", instructions=(
        "Tools for working out what a flight disruption means for travellers. They return references and counts only: "
        "no names or contact details."))
    ToolRegistry([
        GetDisruptionImpact(ImpactAnalyzer(booking, search)),
        FindAlternatives(AlternativeFinder(search)),
    ]).install(server)
    return server


def main() -> None:   # pragma: no cover - needs the real services
    from .config import Settings

    import logging
    logging.getLogger("httpx").setLevel(logging.WARNING)
    settings = Settings.from_env()
    build_server(settings.booking_client(), settings.search_client()).run("stdio")


if __name__ == "__main__":   # pragma: no cover
    main()
