"""The traveller MCP server over the real protocol, with an in-memory client (ADR-0092, ADR-0094)."""

import asyncio

import httpx
from mcp.shared.memory import create_connected_server_and_client_session

from traveller_assistant.booking import BookingClient
from traveller_assistant.flights import FlightSearchClient
from traveller_assistant.help_centre import Article
from traveller_assistant.index import HelpCentreIndex
from traveller_assistant.passages import passages_of
from traveller_assistant.server import build_server
from tests.fakes import WordsEmbedder
from tests.booking_world import GOOD_TOKEN, REFERENCE, SECRETS, BookingWorld
from tests.flight_world import FlightWorld

INDEX = HelpCentreIndex(passages_of(Article("checking-in-online", "Checking in online", "s", (),
                                            "## When you can check in\n\nOnline check-in opens before departure.\n")),
                        WordsEmbedder(), max_distance=0.9)


def with_session(action, world=None, bookings=None):
    async def go():
        booking_world = bookings or BookingWorld()
        server = build_server(INDEX, FlightSearchClient((world or FlightWorld()).client()),
                              BookingClient(booking_world.booking(), booking_world.search()))
        async with create_connected_server_and_client_session(server) as session:
            return await action(session)
    return asyncio.run(go())


def call(name, arguments, world=None, bookings=None):
    async def action(session):
        return await session.call_tool(name, arguments)
    return with_session(action, world, bookings)


def test_offers_exactly_the_three_read_only_tools_so_nothing_that_changes_a_booking_is_there():
    async def action(session):
        return (await session.list_tools()).tools

    tools = {t.name: t for t in with_session(action)}

    assert set(tools) == {"search_help_centre", "search_flights", "get_my_booking"}
    assert all(t.description and t.annotations.readOnlyHint for t in tools.values())


def test_describes_each_tools_inputs_so_a_model_can_call_it():
    async def action(session):
        return {t.name: t.inputSchema for t in (await session.list_tools()).tools}

    schemas = with_session(action)

    assert set(schemas["search_help_centre"]["properties"]) == {"question", "limit"}
    assert schemas["search_help_centre"]["required"] == ["question"]
    assert set(schemas["search_flights"]["properties"]) == {"origin", "destination", "date"}
    assert sorted(schemas["search_flights"]["required"]) == ["date", "destination", "origin"]
    assert sorted(schemas["get_my_booking"]["required"]) == ["booking_token", "reference"]


def test_reads_the_booking_its_token_is_for_and_gives_nothing_personal_back():
    result = call("get_my_booking", {"reference": REFERENCE, "booking_token": GOOD_TOKEN})

    assert not result.isError and result.structuredContent["flights"][0]["flight"] == "SK201"
    assert [s for s in SECRETS if s in str(result.structuredContent) or s in str(result.content)] == []


def test_an_ended_booking_session_comes_back_as_words_the_model_can_tell_the_traveller():
    result = call("get_my_booking", {"reference": REFERENCE, "booking_token": "expired"})

    assert "session has ended" in result.structuredContent["error"]


def test_answers_a_help_centre_question_as_structured_data():
    result = call("search_help_centre", {"question": "when does online check-in open"})

    assert not result.isError
    assert result.structuredContent["passages"][0]["article"] == "checking-in-online"
    assert "note" in result.structuredContent


def test_searches_flights_as_structured_data():
    result = call("search_flights", {"origin": "BOM", "destination": "JFK", "date": "2026-12-01"})

    assert not result.isError
    assert result.structuredContent["total"] == 2
    assert result.structuredContent["itineraries"][1]["connection"]["changes_airport"] is True


def test_a_call_the_tool_refuses_comes_back_in_words_the_model_can_act_on():
    result = call("search_flights", {"origin": "Mumbai", "destination": "JFK", "date": "2026-12-01"})

    assert result.structuredContent == {"error": "origin must be a three-letter airport code, like DXB"}


def test_a_flight_search_that_is_down_comes_back_as_an_error_the_model_can_tell_the_traveller():
    result = call("search_flights", {"origin": "BOM", "destination": "JFK", "date": "2026-12-01"},
                  world=FlightWorld(fail=httpx.ConnectError("refused")))

    assert result.structuredContent["error"].startswith("Flight search is not answering")


def test_an_empty_question_comes_back_as_an_error_too():
    assert call("search_help_centre", {"question": "  "}).structuredContent == {
        "error": "Ask a question: the question was empty."}


def test_tells_a_client_that_it_is_read_only_and_holds_no_credentials():
    async def action(session):
        return (await session.initialize()).instructions

    instructions = with_session(action)

    assert "read-only" in instructions and "no credentials" in instructions
