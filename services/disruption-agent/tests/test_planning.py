from datetime import date, datetime, timedelta, timezone

from disruption_agent.clients import BookingClient, SearchClient
from disruption_agent.planning import AlternativeFinder, ImpactAnalyzer
from tests.world import BOOKING_URL, SEARCH_URL, World

D = date(2026, 11, 2)


def utc(day: int, hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 11, day, hour, minute, tzinfo=timezone.utc)


def clients(world: World):
    search = SearchClient(world.client(SEARCH_URL), world.client(BOOKING_URL))
    return BookingClient(world.client(BOOKING_URL), "agent.ops", "pw"), search


def test_works_out_the_window_each_booking_can_be_moved_within():
    booking, search = clients(World())

    impact = ImpactAnalyzer(booking, search).analyze("SK501", D)

    by_locator = {b.locator: b for b in impact.bookings}
    # a single flight: from the original departure, with no later flight to catch
    assert by_locator["ABC234"].earliest_departure == utc(2, 8, 30)
    assert by_locator["ABC234"].latest_arrival is None
    # arrives from CMB at 06:00 (+1h to connect = 07:00, before the original 08:30), and must land one hour before the
    # 19:00 onward flight
    assert by_locator["DEF567"].earliest_departure == utc(2, 8, 30)
    assert by_locator["DEF567"].latest_arrival == utc(2, 18)


def test_a_late_inbound_flight_moves_the_earliest_departure_later_than_the_original():
    world = World()
    booking, search = clients(world)
    # the inbound flight now lands at 09:30, so the traveller cannot leave before 10:30
    from tests import world as w
    w.LEGS[("SK100", "2026-11-02")] = ("CMB", "DXB", "2026-11-02T04:00:00Z", "2026-11-02T09:30:00Z")
    try:
        impact = ImpactAnalyzer(booking, search).analyze("SK501", D)
    finally:
        w.LEGS[("SK100", "2026-11-02")] = ("CMB", "DXB", "2026-11-02T01:30:00Z", "2026-11-02T06:00:00Z")

    assert {b.locator: b for b in impact.bookings}["DEF567"].earliest_departure == utc(2, 10, 30)


def test_lists_bookings_in_a_stable_order_with_the_disrupted_flight_attached():
    booking, search = clients(World())

    impact = ImpactAnalyzer(booking, search).analyze("SK501", D)

    assert [b.locator for b in impact.bookings] == ["ABC234", "DEF567"]
    assert (impact.flight.origin, impact.flight.destination) == ("DXB", "LHR")
    assert impact.bookings[1].disrupted_segment_index == 1
    assert [s.flight_number for s in impact.bookings[1].segments] == ["SK100", "SK501", "SK700"]


def test_looks_up_each_distinct_flight_once_not_once_per_booking():
    world = World()
    booking, search = clients(world)

    ImpactAnalyzer(booking, search).analyze("SK501", D)

    lookups = world.asked(lambda r: "/departures/" in r.url.path and not r.url.path.endswith("/seats")
                          and "/ops/" not in r.url.path)
    assert sorted(r.url.path.split("/")[4] for r in lookups) == ["SK100", "SK501", "SK700"]


def test_finds_alternatives_in_departure_order_on_the_same_and_the_next_days():
    _, search = clients(World())

    found = AlternativeFinder(search).find("DXB", "LHR", not_before=utc(2, 8, 30), passengers=1)

    assert [(a.flight_number, a.departure_date.isoformat()) for a in found] == [
        ("SK501", "2026-11-02"), ("SK502", "2026-11-02"), ("SK503", "2026-11-02"), ("SK505", "2026-11-02"),
        ("SK501", "2026-11-03")]


def test_leaves_out_flights_that_have_left_and_flights_without_enough_seats():
    _, search = clients(World())
    finder = AlternativeFinder(search)

    after_nine = finder.find("DXB", "LHR", not_before=utc(2, 9, 30), passengers=2)

    assert [a.flight_number for a in after_nine][:2] == ["SK503", "SK505"]   # SK502 has left, and had only 1 seat
    assert all(a.seats_available >= 2 for a in after_nine)


def test_leaves_out_flights_that_land_too_late_for_the_next_leg():
    _, search = clients(World())

    found = AlternativeFinder(search).find("DXB", "LHR", not_before=utc(2, 8, 30), passengers=1, latest_arrival=utc(2, 18))

    assert [a.flight_number for a in found] == ["SK501", "SK502"]  # SK503 lands 18:20, SK505 21:50, next day too late


def test_returns_at_most_the_limit_best_flights():
    _, search = clients(World())

    found = AlternativeFinder(search).find("DXB", "LHR", not_before=utc(2, 0), passengers=1, limit=2)

    assert [a.flight_number for a in found] == ["SK501", "SK502"]


def test_searches_and_counts_seats_once_per_route_day_and_flight_across_many_questions():
    world = World()
    _, search = clients(world)
    finder = AlternativeFinder(search)

    for hour in range(0, 20):
        finder.find("DXB", "LHR", not_before=utc(2, hour), passengers=1)

    assert len(world.asked(lambda r: r.url.path == "/api/v1/itineraries")) == 3  # three days, once each
    seat_calls = world.asked(lambda r: r.url.path.endswith("/seats"))
    assert len(seat_calls) == len({r.url.path for r in seat_calls}) == 5   # one per flight


def test_stays_fast_with_a_very_large_flight():
    """500 bookings on one flight share a handful of distinct legs: the work depends on legs, not bookings."""
    from tests import world as w
    world = World()
    original = list(w.BOOKINGS)
    w.BOOKINGS.extend({"locator": f"B{i:05d}", "passengerCount": 1 + i % 4, "segmentIndex": 0,
                       "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]} for i in range(5000))
    try:
        booking, search = clients(world)
        impact = ImpactAnalyzer(booking, search).analyze("SK501", D)
    finally:
        w.BOOKINGS[:] = original

    assert len(impact.bookings) == 5002
    lookups = world.asked(lambda r: "/departures/" in r.url.path and "/ops/" not in r.url.path)
    assert len(lookups) == 3
