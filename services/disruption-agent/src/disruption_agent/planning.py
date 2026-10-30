"""The two questions re-accommodation comes down to: who is affected and what window can each of them move within, and
which flights could they move to. Pure logic over the clients; no HTTP, no model."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta

from .clients import BookingClient, SearchClient
from .models import AffectedBooking, Alternative, Leg

MIN_CONNECTION = timedelta(minutes=60)   # time needed between landing and the next flight


@dataclass(frozen=True)
class Impact:
    flight: Leg
    bookings: tuple[AffectedBooking, ...]   # sorted by locator, so paging is stable

    @property
    def total_passengers(self) -> int:
        return sum(b.passenger_count for b in self.bookings)


class ImpactAnalyzer:
    """Works out, for every active booking on a disrupted flight, the earliest it could leave and the latest it could
    land without missing a connection. Cost: one booking call, plus one lookup per *distinct* flight in all the
    bookings' routes (the client remembers them), plus O(n * segments) for the arithmetic. Sorting is O(n log n)."""

    def __init__(self, booking: BookingClient, search: SearchClient):
        self._booking = booking
        self._search = search

    def analyze(self, flight_number: str, day: date) -> Impact:
        disrupted = self._search.departure(flight_number, day)
        bookings = [self._plan(raw, disrupted) for raw in self._booking.affected_bookings(flight_number, day)]
        bookings.sort(key=lambda b: b.locator)
        return Impact(flight=disrupted, bookings=tuple(bookings))

    def _plan(self, raw: dict, disrupted: Leg) -> AffectedBooking:
        segments = tuple(self._search.departure(s["flightNumber"], date.fromisoformat(s["departureDate"]))
                         for s in raw["segments"])
        index = raw["segmentIndex"]
        earliest = disrupted.departure                 # never earlier than the flight it replaces was due to leave
        if index > 0:
            earliest = max(earliest, segments[index - 1].arrival + MIN_CONNECTION)
        latest = segments[index + 1].departure - MIN_CONNECTION if index + 1 < len(segments) else None
        return AffectedBooking(locator=raw["locator"], passenger_count=raw["passengerCount"],
                               disrupted_segment_index=index, segments=segments,
                               earliest_departure=earliest, latest_arrival=latest)


class AlternativeFinder:
    """Direct flights on a route that a group could take. Flights are checked in departure order and the search stops
    as soon as `limit` have been found, so it never counts seats on flights it would not return: the seat lookups
    are bounded by `limit` plus the flights that fail the cheaper time checks."""

    def __init__(self, search: SearchClient):
        self._search = search

    def find(self, origin: str, destination: str, *, not_before: datetime, passengers: int = 1,
             latest_arrival: datetime | None = None, within_days: int = 2, limit: int = 8) -> list[Alternative]:
        found: list[Alternative] = []
        first_day = not_before.date()
        for offset in range(within_days + 1):
            day = first_day + timedelta(days=offset)
            for leg in sorted(self._search.direct_flights(origin, destination, day), key=lambda f: f.departure):
                if leg.departure < not_before or (latest_arrival is not None and leg.arrival > latest_arrival):
                    continue    # the cheap checks first: no seat lookup for a flight that cannot work anyway
                seats = self._search.seats_available(leg.flight_number, leg.departure_date)
                if seats >= passengers:
                    found.append(Alternative(**leg.model_dump(), seats_available=seats))
                    if len(found) == limit:
                        return found
        return found
