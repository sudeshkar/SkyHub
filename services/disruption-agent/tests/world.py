"""A small fake SkyHub, served through httpx's MockTransport, so tests need no running services.

One disrupted flight (SK501, DXB to LHR, 2026-11-02 08:30 to 16:20 UTC) and two bookings on it:

* ABC234: 2 passengers, only this flight. Any alternative from 08:30 will do.
* DEF567: 1 passenger, arriving DXB from CMB at 06:00 and flying on from LHR to JFK at 19:00. So it can leave DXB no
  earlier than 08:30 (the original time) and must land at LHR by 18:00 (one hour before the next flight).

Alternatives out of DXB for LHR: SK502 (09:00, lands 16:50, 1 seat), SK503 (10:30, lands 18:20, 150 seats),
SK505 (14:00, lands 21:50, 150 seats), and the next day's SK501 (08:30, lands 16:20, 150 seats).
"""
from __future__ import annotations

import json
from collections.abc import Callable

import httpx

BOOKING_URL = "http://booking.test"
SEARCH_URL = "http://search.test"
TOKEN = "staff-token-that-must-never-reach-a-model"

DISRUPTED = {"flightNumber": "SK501", "departureDate": "2026-11-02"}

LEGS = {
    ("SK501", "2026-11-02"): ("DXB", "LHR", "2026-11-02T08:30:00Z", "2026-11-02T16:20:00Z"),
    ("SK100", "2026-11-02"): ("CMB", "DXB", "2026-11-02T01:30:00Z", "2026-11-02T06:00:00Z"),
    ("SK700", "2026-11-02"): ("LHR", "JFK", "2026-11-02T19:00:00Z", "2026-11-03T02:00:00Z"),
    ("SK502", "2026-11-02"): ("DXB", "LHR", "2026-11-02T09:00:00Z", "2026-11-02T16:50:00Z"),
    ("SK503", "2026-11-02"): ("DXB", "LHR", "2026-11-02T10:30:00Z", "2026-11-02T18:20:00Z"),
    ("SK505", "2026-11-02"): ("DXB", "LHR", "2026-11-02T14:00:00Z", "2026-11-02T21:50:00Z"),
    ("SK501", "2026-11-03"): ("DXB", "LHR", "2026-11-03T08:30:00Z", "2026-11-03T16:20:00Z"),
}
SEATS = {("SK501", "2026-11-02"): 150, ("SK502", "2026-11-02"): 1, ("SK503", "2026-11-02"): 150, ("SK505", "2026-11-02"): 150, ("SK501", "2026-11-03"): 150}

BOOKINGS = [
    {"locator": "ABC234", "passengerCount": 2, "segmentIndex": 0,
     "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]},
    {"locator": "DEF567", "passengerCount": 1, "segmentIndex": 1,
     "segments": [{"flightNumber": "SK100", "departureDate": "2026-11-02"},
                  {"flightNumber": "SK501", "departureDate": "2026-11-02"},
                  {"flightNumber": "SK700", "departureDate": "2026-11-02"}]},
]


def leg_json(flight: str, date: str) -> dict:
    origin, destination, departs, arrives = LEGS[(flight, date)]
    return {"flightNumber": flight, "origin": origin, "destination": destination, "departureTime": departs,
            "arrivalTime": arrives, "aircraftType": "A380", "fare": 540.0, "currency": "USD"}


class World:
    """Records every request, so tests can check what was (and was not) asked."""

    def __init__(self, *, login_ok: bool = True, bookings_status: int = 200, seat_override: dict | None = None,
                 rebook_responses: dict | None = None):
        self.rebook_responses = rebook_responses or {}   # locator -> (status, body); default: moved
        self.rebookings: list[dict] = []
        self.requests: list[httpx.Request] = []
        self.login_ok = login_ok
        self.bookings_status = bookings_status
        self.seats = {**SEATS, **(seat_override or {})}
        self.logins = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path == "/api/v1/ops/sessions":
            self.logins += 1
            if not self.login_ok:
                return httpx.Response(401, json={"title": "Login failed"})
            return httpx.Response(201, json={"accessToken": TOKEN, "tokenType": "Bearer", "expiresAt": "2026-11-02T09:00:00Z",
                                             "username": "agent.ops", "role": "OPS"})
        if path.startswith("/api/v1/ops/pnrs/") and path.endswith("/rebooking"):
            if request.headers.get("Authorization") != f"Bearer {TOKEN}":
                return httpx.Response(401)
            locator = path.split("/")[5]
            body = json.loads(request.content)
            self.rebookings.append({"locator": locator, **body})
            status, answer = self.rebook_responses.get(locator, (200, None))
            if status == 200:
                return httpx.Response(200, json=answer or {
                    "locator": locator, "segmentIndex": body["segmentIndex"], "changed": True,
                    "from": {"flightNumber": "SK501", "departureDate": "2026-11-02"},
                    "to": {"flightNumber": body["flightNumber"], "departureDate": body["departureDate"]}, "seats": ["14A"]})
            return httpx.Response(status, json=answer)
        if "/ops/flights/" in path and path.endswith("/bookings"):
            if request.headers.get("Authorization") != f"Bearer {TOKEN}":
                return httpx.Response(401)
            if self.bookings_status != 200:
                return httpx.Response(self.bookings_status)
            return httpx.Response(200, json={**DISRUPTED, "items": BOOKINGS})
        if path.startswith("/api/v1/flights/") and path.endswith("/seats"):
            _, _, _, _, flight, _, date, _ = path.split("/")
            free = self.seats.get((flight, date))
            if free is None:
                return httpx.Response(404)
            return httpx.Response(200, json={"flightNumber": flight, "departureDate": date,
                                             "seats": [{"seatNumber": f"{i + 1}A", "status": "AVAILABLE"} for i in range(free)]
                                                      + [{"seatNumber": "99Z", "status": "BOOKED"}]})
        if path.startswith("/api/v1/flights/") and "/departures/" in path:
            _, _, _, _, flight, _, date = path.split("/")
            if (flight, date) not in LEGS:
                return httpx.Response(404, json={"title": "Flight not found"})
            return httpx.Response(200, json=leg_json(flight, date))
        if path == "/api/v1/itineraries":
            params = request.url.params
            date = params["date"]
            found = [leg for (flight, d), (o, dest, *_rest) in LEGS.items()
                     if d == date and o == params["origin"] and dest == params["destination"]
                     for leg in [leg_json(flight, d)]]
            return httpx.Response(200, json=[{"legs": [leg], "connections": [], "stops": 0, "totalDurationMinutes": 470,
                                              "fareFrom": 540.0, "currency": "USD"} for leg in found])
        return httpx.Response(404)

    def client(self, base_url: str) -> httpx.Client:
        return httpx.Client(base_url=base_url, transport=httpx.MockTransport(self.handler))

    def asked(self, predicate: Callable[[httpx.Request], bool]) -> list[httpx.Request]:
        return [r for r in self.requests if predicate(r)]


def dump(value) -> str:
    return json.dumps(value, default=str)
