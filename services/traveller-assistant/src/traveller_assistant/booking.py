"""Reads the traveller's own booking with the manage token the website holds for it (ADR-0094).

The token is passed in for each call and kept nowhere. What comes back is built afresh, field by field, rather than
filtered from the booking service's answer, so a field the booking service adds later cannot reach the model by
accident: no names, no contact email, no reference, no barcode. Passengers are known only by their position.

At most 4 flights a booking, so at most 1 + 1 + 4 + 4 calls: the booking, its disruptions, each flight's check-in and
each flight's times (flight-search's public departure lookup, which gets no token).
"""

from __future__ import annotations

import re

import httpx

from traveller_assistant.errors import ServiceError

__all__ = ["BookingClient", "ServiceError", "BOOKING_ENDED", "CANNOT_OPEN", "NOT_ANSWERING"]

BOOKING_ENDED = ("The traveller's booking session has ended. Ask them to open the booking again on the Manage booking "
                 "page, then ask again.")
CANNOT_OPEN = "This booking could not be opened. Ask the traveller to open it again on the Manage booking page."
NOT_ANSWERING = ("The booking service is not answering right now. Tell the traveller to try again in a few minutes; do "
                 "not guess anything about their booking.")

_FLIGHT = re.compile(r"[A-Z0-9]{2}[0-9]{1,4}")
_DAY = re.compile(r"\d{4}-\d{2}-\d{2}")
_SEAT = re.compile(r"[0-9]{1,2}[A-K]")
MAX_REASON = 200


class BookingClient:
    def __init__(self, booking: httpx.Client, flights: httpx.Client):
        self._booking = booking
        self._flights = flights

    def my_booking(self, reference: str, token: str) -> dict:
        headers = {"Authorization": f"Bearer {token}"}
        base = f"/api/v1/pnrs/{reference}"
        pnr = self._get_booking(base, headers)
        disruptions = {d.get("segmentIndex"): d for d in self._get_booking(f"{base}/disruptions", headers).get("items", [])}
        passengers = pnr.get("passengers") or []

        flights = []
        for index, segment in enumerate((pnr.get("segments") or [])[:4]):
            flight, day = str(segment.get("flightNumber", "")), str(segment.get("departureDate", ""))
            if not (_FLIGHT.fullmatch(flight) and _DAY.fullmatch(day)):
                continue
            shown = {"flight": flight, "date": day, **self._times(flight, day)}
            disruption = disruptions.get(index)
            if disruption:
                shown["status"] = "CANCELLED" if disruption.get("type") == "CANCELLED" else "DELAYED"
                if disruption.get("revisedDeparture") and shown["status"] == "DELAYED":
                    shown["now_departs"] = disruption["revisedDeparture"]
                if disruption.get("reason"):
                    shown["reason"] = str(disruption["reason"])[:MAX_REASON]
            shown["seats"] = {f"passenger {n}": _seat(p, index) for n, p in enumerate(passengers, start=1)}
            shown["checked_in"] = self._checked_in(f"{base}/check-ins/{index}", headers)
            flights.append(shown)

        booking = {"status": "CANCELLED" if pnr.get("status") == "CANCELLED" else "CONFIRMED",
                   "passengers": len(passengers), "flights": flights}
        refund = pnr.get("refund")
        if isinstance(refund, dict) and refund.get("status") in ("REFUNDED", "PENDING", "NOT_REFUNDABLE"):
            booking["refund"] = {"status": refund["status"], "amount": refund.get("amount"),
                                 "currency": refund.get("currency")}
        return booking

    def _get_booking(self, path: str, headers: dict) -> dict:
        response = self._send(path, headers)
        if response.status_code == 401:
            raise ServiceError(BOOKING_ENDED)
        if response.status_code in (403, 404):
            raise ServiceError(CANNOT_OPEN)   # another booking and no booking look the same
        if response.status_code != 200:
            raise ServiceError(NOT_ANSWERING)
        return response.json()

    def _checked_in(self, path: str, headers: dict) -> list[str]:
        response = self._send(path, headers)
        if response.status_code in (404, 409):   # not checked in; a cancelled booking has no passes
            return []
        if response.status_code == 401:
            raise ServiceError(BOOKING_ENDED)
        if response.status_code != 200:
            raise ServiceError(NOT_ANSWERING)
        positions = {p.get("passengerIndex") for p in response.json().get("passes", [])}
        return [f"passenger {i + 1}" for i in sorted(p for p in positions if isinstance(p, int))]

    def _send(self, path: str, headers: dict) -> httpx.Response:
        try:
            return self._booking.get(path, headers=headers)
        except httpx.HTTPError:
            raise ServiceError(NOT_ANSWERING) from None   # the exception names the address

    def _times(self, flight: str, day: str) -> dict:
        """The flight's airports and UTC times, from flight-search; nothing if it cannot say (the rest still shows)."""
        try:
            response = self._flights.get(f"/api/v1/flights/{flight}/departures/{day}")
        except httpx.HTTPError:
            return {}
        if response.status_code != 200:
            return {}
        leg = response.json()
        times = {"from": leg.get("origin"), "to": leg.get("destination"), "departs": leg.get("departureTime"),
                 "arrives": leg.get("arrivalTime"), "status": leg.get("status") or "SCHEDULED"}
        if leg.get("revisedDeparture"):
            times["now_departs"] = leg["revisedDeparture"]
        return {k: v for k, v in times.items() if v is not None}


def _seat(passenger: dict, index: int) -> str:
    seats = passenger.get("seats") or []
    seat = str(seats[index]) if index < len(seats) else ""
    return seat if _SEAT.fullmatch(seat) else "unknown"
