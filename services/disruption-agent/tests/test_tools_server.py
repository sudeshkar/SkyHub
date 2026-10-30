import asyncio

from mcp import Client

from disruption_agent.clients import BookingClient, SearchClient
from disruption_agent.server import build_server
from disruption_agent.toolbox import McpToolBox
from tests.world import BOOKING_URL, SEARCH_URL, World


def toolbox_for(world: World):
    search = SearchClient(world.client(SEARCH_URL), world.client(BOOKING_URL))
    booking = BookingClient(world.client(BOOKING_URL), "agent.ops", "pw")
    return Client(build_server(booking, search))


def run(coro):
    return asyncio.run(coro)


def test_exposes_the_two_tools_with_descriptions_and_typed_inputs():
    async def go():
        async with toolbox_for(World()) as client:
            return await McpToolBox(client).specs()

    specs = {s.name: s for s in run(go())}

    assert set(specs) == {"get_disruption_impact", "find_alternatives"}
    assert "flight_number" in specs["get_disruption_impact"].parameters["properties"]
    assert "not_before" in specs["find_alternatives"].parameters["properties"]
    assert all(s.description for s in specs.values())


def test_reports_the_impact_of_a_disruption_with_windows_and_no_personal_data():
    async def go():
        async with toolbox_for(World()) as client:
            return await McpToolBox(client).call("get_disruption_impact",
                                                 {"flight_number": "SK501", "departure_date": "2026-11-02"})

    result = run(go())

    assert result["total_bookings"] == 2 and result["total_passengers"] == 3
    assert result["flight"]["origin"] == "DXB"
    assert [b["locator"] for b in result["bookings"]] == ["ABC234", "DEF567"]
    assert result["bookings"][1]["latest_arrival"] == "2026-11-02T18:00:00Z"
    assert result["next_offset"] is None
    assert "@" not in str(result) and "name" not in {k for b in result["bookings"] for k in b}


def test_pages_through_a_large_impact_without_asking_the_booking_service_again():
    from tests import world as w
    original = list(w.BOOKINGS)
    w.BOOKINGS.extend({"locator": f"B{i:05d}", "passengerCount": 1, "segmentIndex": 0,
                       "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]} for i in range(100))
    world = World()

    async def go():
        async with toolbox_for(world) as client:
            box, pages, offset = McpToolBox(client), [], 0
            while offset is not None:
                page = await box.call("get_disruption_impact", {"flight_number": "SK501", "departure_date": "2026-11-02",
                                                                "offset": offset, "limit": 40})
                pages.append(page)
                offset = page["next_offset"]
            return pages

    try:
        pages = run(go())
    finally:
        w.BOOKINGS[:] = original

    assert [len(p["bookings"]) for p in pages] == [40, 40, 22]
    assert len({b["locator"] for p in pages for b in p["bookings"]}) == 102
    assert len(world.asked(lambda r: r.url.path.endswith("/bookings"))) == 1


def test_refuses_an_absurd_page_size_so_one_answer_can_never_be_unbounded():
    async def go():
        async with toolbox_for(World()) as client:
            return await McpToolBox(client).call("get_disruption_impact", {
                "flight_number": "SK501", "departure_date": "2026-11-02", "limit": 100000})

    assert "error" in run(go())


def test_finds_alternatives_in_departure_order_within_the_window():
    async def go():
        async with toolbox_for(World()) as client:
            return await McpToolBox(client).call("find_alternatives", {
                "origin": "DXB", "destination": "LHR", "not_before": "2026-11-02T08:30:00Z", "passengers": 2,
                "latest_arrival": "2026-11-02T18:00:00Z"})

    result = run(go())

    assert [a["flight_number"] for a in result["alternatives"]] == ["SK501"]      # SK502 has 1 seat; SK503 lands late
    assert result["alternatives"][0]["seats_available"] >= 2


def test_answers_a_bad_question_with_an_error_the_model_can_read_instead_of_crashing():
    async def go():
        async with toolbox_for(World()) as client:
            box = McpToolBox(client)
            return (await box.call("find_alternatives", {"origin": "DXB", "destination": "LHR", "not_before": "tomorrow"}),
                    await box.call("get_disruption_impact", {"flight_number": "NOPE", "departure_date": "2026-11-02"}),
                    await box.call("no_such_tool", {}))

    bad_time, bad_flight, no_tool = run(go())

    assert "not_before" in bad_time["error"]
    assert "error" in bad_flight and "error" in no_tool
