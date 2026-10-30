"""Thin clients for the two SkyHub services the agent reads from. Each hides HTTP, tokens and caching behind a few
methods, so nothing above this layer knows how SkyHub is reached (and a test can swap the HTTP client)."""
from __future__ import annotations

from datetime import date

import httpx

from .cache import TtlCache
from .models import Leg

LEG_TTL_SECONDS = 600     # timetables change rarely
SEATS_TTL_SECONDS = 20    # seats go all the time: a count older than this is not trusted
CACHE_ENTRIES = 4096      # bounds memory however many flights are asked about


class ServiceError(Exception):
    """A SkyHub service could not answer. Messages never contain credentials."""


class BookingClient:
    """Reads the bookings on a flight with a staff account (ADR-0017, ADR-0022). Logs in once and reuses the token;
    if the service refuses it (it expired) logs in again once and retries."""

    def __init__(self, http: httpx.Client, username: str, password: str):
        self._http = http
        self._username = username
        self._password = password
        self._token: str | None = None

    def _login(self) -> str:
        response = self._http.post("/api/v1/ops/sessions", json={"username": self._username, "password": self._password})
        if response.status_code != 201:
            raise ServiceError(f"Staff login was refused (HTTP {response.status_code})")
        self._token = response.json()["accessToken"]
        return self._token

    def _send(self, method: str, path: str, **kwargs) -> httpx.Response:
        """One authorised call. If the service refuses the token (it expired) logs in again, once, and retries."""
        response = None
        for attempt in (1, 2):
            token = self._token or self._login()
            response = self._http.request(method, path, headers={"Authorization": f"Bearer {token}"}, **kwargs)
            if response.status_code == 401 and attempt == 1:
                self._token = None
                continue
            break
        return response

    def affected_bookings(self, flight_number: str, day: date) -> list[dict]:
        response = self._send("GET", f"/api/v1/ops/flights/{flight_number}/departures/{day.isoformat()}/bookings")
        if response.status_code != 200:
            raise ServiceError(f"The booking service answered {response.status_code} for the bookings on {flight_number}")
        return response.json()["items"]

    def rebook(self, locator: str, segment_index: int, flight_number: str, day: date) -> dict:
        """Asks the booking service to move one flight of a booking. The service re-checks every rule and may refuse; its
        reason is passed on. Only ever called after a person approved (see apply.py)."""
        response = self._send("POST", f"/api/v1/ops/pnrs/{locator}/rebooking", json={
            "segmentIndex": segment_index, "flightNumber": flight_number, "departureDate": day.isoformat()})
        if response.status_code != 200:
            try:
                detail = response.json().get("detail")
            except ValueError:
                detail = None
            raise ServiceError(f"The booking service refused the move of {locator}: {detail}" if detail
                               else f"The booking service answered {response.status_code} for the move of {locator}")
        return response.json()


class SearchClient:
    """Reads timetables from flight-search and free seats from booking. Every answer is remembered for a while, so the
    number of HTTP calls depends on how many *different* flights are involved, not on how many bookings or questions."""

    def __init__(self, search_http: httpx.Client, booking_http: httpx.Client,
                 legs: TtlCache | None = None, seats: TtlCache | None = None):
        self._search = search_http
        self._booking = booking_http
        self._legs: TtlCache = legs or TtlCache(LEG_TTL_SECONDS, CACHE_ENTRIES)
        self._seats: TtlCache = seats or TtlCache(SEATS_TTL_SECONDS, CACHE_ENTRIES)

    def departure(self, flight_number: str, day: date) -> Leg:
        return self._legs.get_or_compute(("departure", flight_number, day), lambda: self._fetch_departure(flight_number, day))

    def direct_flights(self, origin: str, destination: str, day: date) -> tuple[Leg, ...]:
        return self._legs.get_or_compute(("route", origin, destination, day),
                                         lambda: self._fetch_direct(origin, destination, day))

    def seats_available(self, flight_number: str, day: date) -> int:
        return self._seats.get_or_compute((flight_number, day), lambda: self._fetch_seats(flight_number, day))

    def _fetch_departure(self, flight_number: str, day: date) -> Leg:
        response = self._search.get(f"/api/v1/flights/{flight_number}/departures/{day.isoformat()}")
        if response.status_code == 404:
            raise ServiceError(f"{flight_number} does not operate on {day}")
        if response.status_code != 200:
            raise ServiceError(f"The flight search answered {response.status_code} for {flight_number}")
        return Leg.from_api(response.json())

    def _fetch_direct(self, origin: str, destination: str, day: date) -> tuple[Leg, ...]:
        response = self._search.get("/api/v1/itineraries", params={
            "origin": origin, "destination": destination, "date": day.isoformat(), "passengers": 1})
        if response.status_code != 200:
            raise ServiceError(f"The flight search answered {response.status_code} for {origin} to {destination}")
        return tuple(Leg.from_api(it["legs"][0]) for it in response.json() if it["stops"] == 0)

    def _fetch_seats(self, flight_number: str, day: date) -> int:
        response = self._booking.get(f"/api/v1/flights/{flight_number}/departures/{day.isoformat()}/seats")
        if response.status_code == 404:
            return 0
        if response.status_code != 200:
            raise ServiceError(f"The booking service answered {response.status_code} for the seats on {flight_number}")
        return sum(1 for seat in response.json()["seats"] if seat["status"] == "AVAILABLE")
