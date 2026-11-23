"""Settings come from the environment. The traveller server has no secret to read: only where flight-search and the
booking service are. A traveller's manage token reaches it with each call, never from here (ADR-0094)."""

from __future__ import annotations

import os
from dataclasses import dataclass

import httpx

from traveller_assistant.booking import BookingClient
from traveller_assistant.flights import FlightSearchClient

# A traveller is waiting: fail fast rather than hold the conversation (ADR-0092).
TIMEOUT = httpx.Timeout(5.0, connect=2.0)


@dataclass(frozen=True)
class Settings:
    search_url: str
    booking_url: str

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(search_url=os.environ.get("SKYHUB_SEARCH_API_URL", "http://localhost:8081"),
                   booking_url=os.environ.get("SKYHUB_BOOKING_API_URL", "http://localhost:8082"))

    def flight_search(self) -> FlightSearchClient:
        return FlightSearchClient(httpx.Client(base_url=self.search_url, timeout=TIMEOUT))

    def bookings(self) -> BookingClient:
        return BookingClient(httpx.Client(base_url=self.booking_url, timeout=TIMEOUT),
                             httpx.Client(base_url=self.search_url, timeout=TIMEOUT))
