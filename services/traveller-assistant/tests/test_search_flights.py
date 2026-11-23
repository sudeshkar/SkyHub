import pytest

from traveller_assistant.flights import FlightSearchClient
from traveller_assistant.tools import MAX_ITINERARIES, NO_FLIGHTS, ABOUT_FLIGHTS, SearchFlights, ToolInputError
from tests.flight_world import BOM_JFK, FlightWorld, leg


def search(world=None, **arguments):
    return SearchFlights(FlightSearchClient((world or FlightWorld()).client())).run(**arguments)


def test_shows_a_connection_with_its_flights_times_status_and_fare():
    found = search(origin="BOM", destination="JFK", date="2026-12-01")

    first = found["itineraries"][0]
    assert first["flights"] == [
        {"flight": "SK201", "from": "BOM", "to": "DXB", "departs": "2026-12-01T02:00:00Z",
         "arrives": "2026-12-01T05:10:00Z", "status": "SCHEDULED"},
        {"flight": "SK701", "from": "DXB", "to": "JFK", "departs": "2026-12-01T07:30:00Z",
         "arrives": "2026-12-01T21:40:00Z", "status": "DELAYED", "now_departs": "2026-12-01T08:15:00Z"},
    ]
    assert first["stops"] == 1
    assert first["connection"] == {"at": "DXB", "leaves_from": "DXB", "layover_minutes": 185, "changes_airport": False}
    assert (first["fare_from"], first["currency"], first["duration_minutes"]) == (620.0, "USD", 1180)


def test_says_when_a_connection_changes_airport_and_how_long_the_transfer_takes():
    second = search(origin="BOM", destination="JFK", date="2026-12-01")["itineraries"][1]

    assert second["connection"] == {"at": "DXB", "leaves_from": "DWC", "layover_minutes": 230, "changes_airport": True,
                                    "transfer_minutes": 120}


def test_a_direct_flight_has_no_connection():
    direct = search(origin="DXB", destination="LHR", date="2026-12-01")["itineraries"][0]

    assert direct["stops"] == 0 and direct["connection"] is None


def test_tells_the_model_how_to_read_the_answer():
    found = search(origin="DXB", destination="LHR", date="2026-12-01")

    assert found["note"] == ABOUT_FLIGHTS
    assert "UTC" in ABOUT_FLIGHTS and "from" in ABOUT_FLIGHTS and "cancelled" in ABOUT_FLIGHTS


def test_accepts_airport_codes_in_any_case_and_sends_them_in_capitals():
    world = FlightWorld()

    search(world, origin="bom", destination="Jfk", date="2026-12-01")

    assert dict(world.requests[0].url.params)["origin"] == "BOM"
    assert dict(world.requests[0].url.params)["destination"] == "JFK"


def test_no_flights_on_the_day_says_so_and_suggests_another_day():
    found = search(origin="CMB", destination="LHR", date="2026-12-01")

    assert found == {"itineraries": [], "total": 0, "note": NO_FLIGHTS}
    assert "another day" in NO_FLIGHTS


def test_returns_at_most_ten_itineraries_and_says_how_many_there_were():
    many = [dict(BOM_JFK[0], fareFrom=600.0 + i) for i in range(25)]

    found = search(FlightWorld(routes={("BOM", "JFK"): many}), origin="BOM", destination="JFK", date="2026-12-01")

    assert len(found["itineraries"]) == MAX_ITINERARIES == 10
    assert found["total"] == 25
    assert [i["fare_from"] for i in found["itineraries"]] == [600.0 + i for i in range(10)]


def test_a_flight_with_no_status_is_scheduled():
    flight = search(FlightWorld(routes={("DXB", "LHR"): [{
        "legs": [leg("SK501", "DXB", "LHR", "2026-12-01T08:30:00Z", "2026-12-01T16:20:00Z")],
        "connections": [], "stops": 0, "totalDurationMinutes": 470, "fareFrom": 540.0, "currency": "USD"}]}),
        origin="DXB", destination="LHR", date="2026-12-01")["itineraries"][0]["flights"][0]

    assert flight["status"] == "SCHEDULED" and "now_departs" not in flight


@pytest.mark.parametrize("arguments, message", [
    (dict(origin="BO", destination="JFK", date="2026-12-01"), "origin must be a three-letter airport code"),
    (dict(origin="BOM", destination="J1K", date="2026-12-01"), "destination must be a three-letter airport code"),
    (dict(origin="Mumbai", destination="JFK", date="2026-12-01"), "origin must be a three-letter airport code"),
    (dict(origin="BOM", destination="bom", date="2026-12-01"), "origin and destination must be different"),
    (dict(origin="BOM", destination="JFK", date="1 December"), "date must be a date like 2026-12-01"),
    (dict(origin="BOM", destination="JFK", date="2026-02-30"), "date must be a date like 2026-12-01"),
])
def test_refuses_a_search_that_makes_no_sense_before_asking_flight_search(arguments, message):
    world = FlightWorld()

    with pytest.raises(ToolInputError, match=message):
        search(world, **arguments)
    assert world.requests == []
