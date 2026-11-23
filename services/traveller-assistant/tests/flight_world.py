"""A small fake flight-search, served through httpx's MockTransport, so tests need no running service.

On 2026-12-01 from BOM to JFK: a one-stop journey through DXB on the same airport (SK201 then SK701, the second one
delayed) and one that changes airport to DWC (SK203 then SK703). From DXB to LHR: one direct flight, SK501.
"""

from __future__ import annotations

import httpx

SEARCH_URL = "http://flight-search.test"


def leg(flight, origin, destination, departs, arrives, status=None, revised=None):
    leg = {"flightNumber": flight, "origin": origin, "destination": destination, "departureTime": departs,
           "arrivalTime": arrives, "aircraftType": "A350", "fare": 300.0, "currency": "USD"}
    if status:
        leg["status"] = status
    if revised:
        leg["revisedDeparture"] = revised
    return leg


BOM_JFK = [
    {"legs": [leg("SK201", "BOM", "DXB", "2026-12-01T02:00:00Z", "2026-12-01T05:10:00Z"),
              leg("SK701", "DXB", "JFK", "2026-12-01T07:30:00Z", "2026-12-01T21:40:00Z",
                  status="DELAYED", revised="2026-12-01T08:15:00Z")],
     "connections": [{"arrivalAirport": "DXB", "departureAirport": "DXB", "layoverMinutes": 185,
                      "requiresAirportTransfer": False, "transferMinutes": 0}],
     "stops": 1, "totalDurationMinutes": 1180, "fareFrom": 620.0, "currency": "USD"},
    {"legs": [leg("SK203", "BOM", "DXB", "2026-12-01T03:00:00Z", "2026-12-01T06:10:00Z"),
              leg("SK703", "DWC", "JFK", "2026-12-01T10:00:00Z", "2026-12-02T00:30:00Z")],
     "connections": [{"arrivalAirport": "DXB", "departureAirport": "DWC", "layoverMinutes": 230,
                      "requiresAirportTransfer": True, "transferMinutes": 120}],
     "stops": 1, "totalDurationMinutes": 1290, "fareFrom": 580.0, "currency": "USD"},
]
DXB_LHR = [
    {"legs": [leg("SK501", "DXB", "LHR", "2026-12-01T08:30:00Z", "2026-12-01T16:20:00Z")],
     "connections": [], "stops": 0, "totalDurationMinutes": 470, "fareFrom": 540.0, "currency": "USD"},
]


class FlightWorld:
    """Records every request, so tests can check what was (and was not) sent."""

    def __init__(self, *, status: int = 200, routes: dict | None = None, fail: Exception | None = None):
        self.status = status
        self.routes = routes if routes is not None else {("BOM", "JFK"): BOM_JFK, ("DXB", "LHR"): DXB_LHR}
        self.fail = fail
        self.requests: list[httpx.Request] = []

    def client(self) -> httpx.Client:
        return httpx.Client(base_url=SEARCH_URL, transport=httpx.MockTransport(self._handle))

    def _handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.fail:
            raise self.fail
        if self.status == 400:
            return httpx.Response(400, json={"title": "Bad Request", "status": 400,
                                             "detail": "Origin and destination must be different airports"})
        if self.status != 200:
            return httpx.Response(self.status, text="upstream trouble")
        if request.url.path != "/api/v1/itineraries":
            return httpx.Response(404)
        params = request.url.params
        return httpx.Response(200, json=self.routes.get((params["origin"], params["destination"]), []))
