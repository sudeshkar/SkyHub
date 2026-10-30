"""Runs the agent against the fake world through a real in-process MCP server, the way production does."""
from __future__ import annotations

import asyncio
from datetime import date

from mcp import Client

from disruption_agent.agent import DisruptionAgent
from disruption_agent.clients import BookingClient, SearchClient
from disruption_agent.server import build_server
from disruption_agent.toolbox import McpToolBox, unwrapped
from tests.world import BOOKING_URL, SEARCH_URL, World


def run_agent(model, world: World | None = None, **options):
    world = world or World()

    async def go():
        search = SearchClient(world.client(SEARCH_URL), world.client(BOOKING_URL))
        booking = BookingClient(world.client(BOOKING_URL), "agent.ops", "pw")
        async with unwrapped(), Client(build_server(booking, search)) as client:
            return await DisruptionAgent(model, McpToolBox(client), **options).run("SK501", date(2026, 11, 2))

    return asyncio.run(go())
