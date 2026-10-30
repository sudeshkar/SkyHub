"""Small builders so tests say what matters and nothing else."""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone

from disruption_agent.models import AffectedBooking, Alternative, Leg


def utc(day: int, hour: int, minute: int = 0) -> datetime:
    return datetime(2026, 11, day, hour, minute, tzinfo=timezone.utc)


def leg(flight: str, origin: str, destination: str, departs: datetime, hours: float = 7.8) -> Leg:
    return Leg(flight_number=flight, origin=origin, destination=destination, departure=departs,
               arrival=departs + timedelta(hours=hours), fare=540.0, currency="USD")


def booking(locator: str, passengers: int = 1, *, earliest: datetime = utc(2, 8, 30),
            latest: datetime | None = None) -> AffectedBooking:
    flight = leg("SK501", "DXB", "LHR", utc(2, 8, 30))
    return AffectedBooking(locator=locator, passenger_count=passengers, disrupted_segment_index=0, segments=(flight,),
                           earliest_departure=earliest, latest_arrival=latest)


def alternative(flight: str, departs: datetime, seats: int = 100, *, origin: str = "DXB", destination: str = "LHR",
                hours: float = 7.8) -> Alternative:
    base = leg(flight, origin, destination, departs, hours)
    return Alternative(**base.model_dump(), seats_available=seats)


DISRUPTED = ("SK501", date(2026, 11, 2))
