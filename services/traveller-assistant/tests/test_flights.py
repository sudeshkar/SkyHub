from datetime import date

import httpx
import pytest

from traveller_assistant.flights import FlightSearchClient, ServiceError
from traveller_assistant.tools import ToolInputError
from tests.flight_world import BOM_JFK, FlightWorld


def client(world):
    return FlightSearchClient(world.client())


def test_asks_flight_search_for_the_route_on_the_day():
    world = FlightWorld()

    client(world).itineraries("BOM", "JFK", date(2026, 12, 1))

    (request,) = world.requests
    assert request.method == "GET" and request.url.path == "/api/v1/itineraries"
    assert dict(request.url.params) == {"origin": "BOM", "destination": "JFK", "date": "2026-12-01"}


def test_returns_what_flight_search_answered():
    assert client(FlightWorld()).itineraries("BOM", "JFK", date(2026, 12, 1)) == BOM_JFK


def test_sends_no_credentials_because_it_holds_none():
    world = FlightWorld()

    client(world).itineraries("BOM", "JFK", date(2026, 12, 1))

    assert "authorization" not in {k.lower() for k in world.requests[0].headers}


def test_passes_on_what_flight_search_says_is_wrong_with_a_search():
    with pytest.raises(ToolInputError, match="Origin and destination must be different airports"):
        client(FlightWorld(status=400)).itineraries("DXB", "DXB", date(2026, 12, 1))


@pytest.mark.parametrize("world", [FlightWorld(status=503), FlightWorld(status=500),
                                   FlightWorld(fail=httpx.ConnectError("refused")),
                                   FlightWorld(fail=httpx.ReadTimeout("slow"))])
def test_a_flight_search_that_cannot_answer_is_a_service_error_that_says_so_plainly(world):
    with pytest.raises(ServiceError, match="Flight search is not answering"):
        client(world).itineraries("BOM", "JFK", date(2026, 12, 1))


def test_a_service_error_never_shows_the_address_of_the_service():
    with pytest.raises(ServiceError) as raised:
        client(FlightWorld(fail=httpx.ConnectError("refused"))).itineraries("BOM", "JFK", date(2026, 12, 1))

    assert "flight-search.test" not in str(raised.value)
