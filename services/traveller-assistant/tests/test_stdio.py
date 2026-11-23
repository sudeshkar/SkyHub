"""The real server process, started the way the crew will start it: over stdin and stdout (ADR-0092).

It proves that nothing but protocol messages reaches stdout (a stray print or a download progress bar there would break
every client) and that the server builds the real index. It uses the real embedding model, cached after its first
download, so it takes a few seconds.
"""

import asyncio
import sys

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import get_default_environment, stdio_client

# Nothing listens here, so a flight search fails at once, as it would with flight-search down.
UNREACHABLE = "http://127.0.0.1:9"


def test_the_server_answers_over_stdio_with_the_real_help_centre_and_survives_flight_search_being_down():
    async def go():
        parameters = StdioServerParameters(command=sys.executable, args=["-m", "traveller_assistant.server"],
                                           env={**get_default_environment(), "SKYHUB_SEARCH_API_URL": UNREACHABLE})
        async with stdio_client(parameters) as (read, write), ClientSession(read, write) as session:
            await session.initialize()
            names = {t.name for t in (await session.list_tools()).tools}
            help_answer = await session.call_tool("search_help_centre", {"question": "When can I check in online?"})
            flights_answer = await session.call_tool("search_flights",
                                                     {"origin": "DXB", "destination": "LHR", "date": "2026-12-01"})
            return names, help_answer, flights_answer

    names, help_answer, flights_answer = asyncio.run(asyncio.wait_for(go(), timeout=120))

    assert names == {"search_help_centre", "search_flights", "get_my_booking"}
    assert help_answer.structuredContent["passages"][0]["article"] == "checking-in-online"
    assert flights_answer.structuredContent["error"].startswith("Flight search is not answering")
