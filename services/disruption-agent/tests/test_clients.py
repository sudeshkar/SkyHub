from datetime import date, datetime, timezone

import pytest

from disruption_agent.clients import BookingClient, SearchClient, ServiceError
from tests.world import BOOKING_URL, SEARCH_URL, TOKEN, World


def booking(world: World) -> BookingClient:
    return BookingClient(world.client(BOOKING_URL), "agent.ops", "a-long-service-passphrase")


def search(world: World) -> SearchClient:
    return SearchClient(world.client(SEARCH_URL), world.client(BOOKING_URL))


def test_logs_in_once_and_reuses_the_token_for_later_calls():
    world = World()
    client = booking(world)

    client.affected_bookings("SK501", date(2026, 11, 2))
    client.affected_bookings("SK501", date(2026, 11, 2))

    assert world.logins == 1


def test_asks_for_the_bookings_with_its_staff_token_and_gets_no_personal_data():
    world = World()

    result = booking(world).affected_bookings("SK501", date(2026, 11, 2))

    asked = world.asked(lambda r: r.url.path.endswith("/bookings"))[0]
    assert asked.headers["Authorization"] == f"Bearer {TOKEN}"
    assert [b["locator"] for b in result] == ["ABC234", "DEF567"]
    assert all(set(b) == {"locator", "passengerCount", "segmentIndex", "segments"} for b in result)


def test_logs_in_again_once_when_the_token_is_refused():
    world = World()
    client = booking(world)
    client.affected_bookings("SK501", date(2026, 11, 2))
    client._token = "expired"  # the token went stale between two calls

    client.affected_bookings("SK501", date(2026, 11, 2))

    assert world.logins == 2


def test_reports_a_refused_login_without_echoing_the_password():
    client = booking(World(login_ok=False))

    with pytest.raises(ServiceError) as failure:
        client.affected_bookings("SK501", date(2026, 11, 2))

    assert "login" in str(failure.value).lower()
    assert "a-long-service-passphrase" not in str(failure.value)


def test_reports_a_server_error_as_a_service_error():
    client = booking(World(bookings_status=500))

    with pytest.raises(ServiceError, match="500"):
        client.affected_bookings("SK501", date(2026, 11, 2))


def test_reads_a_flight_with_its_times_in_utc():
    leg = search(World()).departure("SK501", date(2026, 11, 2))

    assert (leg.origin, leg.destination) == ("DXB", "LHR")
    assert leg.departure == datetime(2026, 11, 2, 8, 30, tzinfo=timezone.utc)
    assert leg.arrival == datetime(2026, 11, 2, 16, 20, tzinfo=timezone.utc)


def test_reports_an_unknown_flight_as_a_service_error():
    with pytest.raises(ServiceError, match="SK999"):
        search(World()).departure("SK999", date(2026, 11, 2))


def test_looks_a_flight_up_once_however_often_it_is_asked_for():
    world = World()
    client = search(world)

    for _ in range(50):
        client.departure("SK501", date(2026, 11, 2))

    assert len(world.asked(lambda r: r.url.path == "/api/v1/flights/SK501/departures/2026-11-02")) == 1


def test_finds_the_direct_flights_on_a_route_and_date_and_searches_once():
    world = World()
    client = search(world)

    flights = client.direct_flights("DXB", "LHR", date(2026, 11, 2))
    client.direct_flights("DXB", "LHR", date(2026, 11, 2))

    assert {f.flight_number for f in flights} == {"SK501", "SK502", "SK503", "SK505"}
    assert all(f.origin == "DXB" and f.destination == "LHR" for f in flights)
    assert len(world.asked(lambda r: r.url.path == "/api/v1/itineraries")) == 1


def test_counts_free_seats_once_and_treats_an_unknown_flight_as_having_none():
    world = World()
    client = search(world)

    assert client.seats_available("SK502", date(2026, 11, 2)) == 1
    assert client.seats_available("SK502", date(2026, 11, 2)) == 1
    assert client.seats_available("SK999", date(2026, 11, 2)) == 0
    assert len(world.asked(lambda r: r.url.path.endswith("/SK502/departures/2026-11-02/seats"))) == 1
