"""A thin client for flight-search's public itinerary search (ADR-0092). It holds no credentials: flight-search lets
anyone search, as the website's visitors do."""

from __future__ import annotations

from datetime import date

import httpx

from traveller_assistant.errors import ServiceError, ToolInputError

NOT_ANSWERING = ("Flight search is not answering right now. Tell the traveller to try again in a few minutes; "
                 "do not guess which flights there are.")


class FlightSearchClient:
    def __init__(self, http: httpx.Client):
        self._http = http

    def itineraries(self, origin: str, destination: str, day: date) -> list[dict]:
        """Flight-search's answer as it is: direct and one-stop itineraries, earliest first."""
        try:
            response = self._http.get("/api/v1/itineraries", params={
                "origin": origin, "destination": destination, "date": day.isoformat()})
        except httpx.HTTPError:
            # The exception names the address; the model and the traveller need only know it is down.
            raise ServiceError(NOT_ANSWERING) from None
        if response.status_code == 400:
            raise ToolInputError(_detail(response) or "Flight search refused this search.")
        if response.status_code != 200:
            raise ServiceError(NOT_ANSWERING)
        return response.json()


def _detail(response: httpx.Response) -> str | None:
    try:
        detail = response.json().get("detail")
    except (ValueError, AttributeError):
        return None
    return detail if isinstance(detail, str) else None
