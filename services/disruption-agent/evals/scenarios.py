"""Worlds to judge a planner on. Each one changes the fake SkyHub for the duration of a `with`, then puts it back."""
from __future__ import annotations

import contextlib
import random
from collections.abc import Iterator
from dataclasses import dataclass

from tests import world as w
from tests.world import World

FLIGHTS = [("SK501", "2026-11-02"), ("SK502", "2026-11-02"), ("SK503", "2026-11-02"), ("SK505", "2026-11-02"),
           ("SK501", "2026-11-03")]


@dataclass(frozen=True)
class Scenario:
    name: str
    description: str
    bookings: list[dict]
    seats: dict


def _single(locator: str, pax: int) -> dict:
    return {"locator": locator, "passengerCount": pax, "segmentIndex": 0,
            "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]}


def _connecting(locator: str, pax: int) -> dict:
    return {"locator": locator, "passengerCount": pax, "segmentIndex": 1, "segments": [
        {"flightNumber": "SK100", "departureDate": "2026-11-02"}, {"flightNumber": "SK501", "departureDate": "2026-11-02"},
        {"flightNumber": "SK700", "departureDate": "2026-11-02"}]}


def standard() -> list[Scenario]:
    return [
        Scenario("two bookings, plenty of room", "the example world: everyone can be moved",
                 list(w.BOOKINGS), {}),
        Scenario("a full day", "every same-day alternative is nearly full, so people must be split or handed over",
                 [_single(f"F{i:04d}", 1 + i % 3) for i in range(40)] + [_connecting(f"C{i:04d}", 1) for i in range(5)],
                 {("SK502", "2026-11-02"): 2, ("SK503", "2026-11-02"): 12, ("SK505", "2026-11-02"): 12,
                  ("SK501", "2026-11-03"): 20}),
        Scenario("nothing flies", "no free seats anywhere: the right answer is a person for every booking",
                 [_single(f"N{i:04d}", 2) for i in range(10)],
                 {key: 0 for key in FLIGHTS}),
        Scenario("a fully booked A380", "500 bookings: the model must be fed in batches",
                 [_single(f"L{i:04d}", 1 + i % 4) for i in range(500)],
                 {("SK503", "2026-11-02"): 400, ("SK505", "2026-11-02"): 400, ("SK501", "2026-11-03"): 400}),
    ]


def random_world(rng: random.Random) -> Scenario:
    bookings = [(_connecting if rng.random() < 0.3 else _single)(f"R{i:04d}", rng.randint(1, 4))
                for i in range(rng.randint(1, 60))]
    return Scenario("random", "fuzz", bookings, {key: rng.randint(0, 10) for key in FLIGHTS})


@contextlib.contextmanager
def installed(scenario: Scenario) -> Iterator[World]:
    original = list(w.BOOKINGS)
    w.BOOKINGS[:] = scenario.bookings
    try:
        yield World(seat_override=scenario.seats)
    finally:
        w.BOOKINGS[:] = original
