"""A small fake booking service and flight-search, through httpx's MockTransport (ADR-0094).

Booking ABC234, opened with the manage token GOOD_TOKEN: two passengers (Ada and Alan Lovelace), BOM to DXB on SK201
then DXB to JFK on SK701, 2026-12-01. Passenger 1 is checked in on SK201; nobody on SK701, which is delayed. The
booking's answers are full of what must never reach the model: names, the contact email, barcodes.
"""

from __future__ import annotations

import httpx

BOOKING_URL = "http://booking.test"
SEARCH_URL = "http://flight-search.test"
REFERENCE = "ABC234"
GOOD_TOKEN = "eyJhbGciOiJIUzI1NiJ9.manage-token-that-must-never-reach-a-model.sig"
SECRETS = ("Ada", "Alan", "Lovelace", "LOVELACE", "ada@example.com", REFERENCE, GOOD_TOKEN, "SIGNED-BARCODE")

PNR = {
    "locator": REFERENCE, "status": "CONFIRMED", "contactEmail": "ada@example.com", "createdAt": "2026-11-20T10:00:00Z",
    "segments": [{"flightNumber": "SK201", "departureDate": "2026-12-01"},
                 {"flightNumber": "SK701", "departureDate": "2026-12-01"}],
    "passengers": [{"firstName": "Ada", "lastName": "Lovelace", "seats": ["12A", "30A"]},
                   {"firstName": "Alan", "lastName": "Lovelace", "seats": ["12B", "30B"]}],
}
DISRUPTIONS = {"items": [{"segmentIndex": 1, "flightNumber": "SK701", "departureDate": "2026-12-01", "type": "DELAYED",
                          "revisedDeparture": "2026-12-01T08:15:00Z", "reason": "Late inbound aircraft"}]}
PASS = {"passengerName": "LOVELACE/ADA", "passengerIndex": 0, "locator": REFERENCE, "flightNumber": "SK201",
        "departureDate": "2026-12-01", "seatNumber": "12A", "sequence": 7, "barcode": "SIGNED-BARCODE"}
DEPARTURES = {
    ("SK201", "2026-12-01"): {"flightNumber": "SK201", "origin": "BOM", "destination": "DXB",
                              "departureTime": "2026-12-01T02:00:00Z", "arrivalTime": "2026-12-01T05:10:00Z",
                              "aircraftType": "A350", "fare": 300.0, "currency": "USD"},
    ("SK701", "2026-12-01"): {"flightNumber": "SK701", "origin": "DXB", "destination": "JFK",
                              "departureTime": "2026-12-01T07:30:00Z", "arrivalTime": "2026-12-01T21:40:00Z",
                              "aircraftType": "A380", "fare": 320.0, "currency": "USD", "status": "DELAYED",
                              "revisedDeparture": "2026-12-01T08:15:00Z"},
}


class BookingWorld:
    """Records every request, so tests can check what was sent where."""

    def __init__(self, *, pnr: dict | None = None, booking_status: int | None = None, fail: Exception | None = None,
                 departures: dict | None = None, check_ins: dict | None = None):
        self.pnr = pnr or PNR
        self.booking_status = booking_status
        self.fail = fail
        self.departures = DEPARTURES if departures is None else departures
        self.check_ins = {0: (200, {"passes": [PASS]})} if check_ins is None else check_ins
        self.requests: list[httpx.Request] = []

    def booking(self) -> httpx.Client:
        return httpx.Client(base_url=BOOKING_URL, transport=httpx.MockTransport(self._booking))

    def search(self) -> httpx.Client:
        return httpx.Client(base_url=SEARCH_URL, transport=httpx.MockTransport(self._search))

    def _booking(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.fail:
            raise self.fail
        if self.booking_status:
            return httpx.Response(self.booking_status, json={"detail": "upstream says no"})
        if request.headers.get("authorization") != f"Bearer {GOOD_TOKEN}":
            return httpx.Response(401, json={"detail": "Missing, invalid or expired token"})
        path = request.url.path
        base = f"/api/v1/pnrs/{self.pnr['locator']}"
        if not path.startswith(base):
            return httpx.Response(403, json={"detail": "The token belongs to a different booking"})
        if path == base:
            return httpx.Response(200, json=self.pnr)
        if path == f"{base}/disruptions":
            return httpx.Response(200, json=DISRUPTIONS)
        if path.startswith(f"{base}/check-ins/"):
            status, body = self.check_ins.get(int(path.rsplit("/", 1)[1]), (404, {"detail": "Not checked in"}))
            return httpx.Response(status, json=body)
        return httpx.Response(404)

    def _search(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        _, _, _, _, flight, _, day = request.url.path.split("/")
        leg = self.departures.get((flight, day))
        return httpx.Response(200, json=leg) if leg else httpx.Response(404, json={"detail": "No such flight"})
