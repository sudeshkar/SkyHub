"""An independent judge of an answer. It re-derives every rule from the raw world data and shares no code with the guardrails,
so a mistake in one cannot hide a mistake in the other."""
from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta

from disruption_agent.models import Report
from tests import world as w

CONNECTION = timedelta(minutes=60)


def _time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def violations(report: Report, bookings_on_flight: list[dict], seats: dict) -> list[str]:
    """Everything wrong with a report, as sentences. An empty list means the answer is safe to show a person."""
    problems: list[str] = []
    bookings = {b["locator"]: b for b in bookings_on_flight}
    answered = Counter(p.locator for p in report.proposals)

    if set(answered) != set(bookings) or any(count != 1 for count in answered.values()):
        problems.append("every booking must get exactly one proposal")
    if report.bookings_total != len(bookings):
        problems.append("the total does not match the number of bookings")

    seats_used: Counter = Counter()
    disrupted_departs = _time(w.LEGS[("SK501", "2026-11-02")][2])
    for p in report.proposals:
        if p.action != "REBOOK" or p.locator not in bookings:
            continue
        key = (p.flight_number, p.departure_date.isoformat())
        if key not in w.LEGS:
            problems.append(f"{p.locator}: {key} is not a real flight")
            continue
        origin, destination, departs, arrives = w.LEGS[key]
        booking = bookings[p.locator]
        legs = [w.LEGS[(s["flightNumber"], s["departureDate"])] for s in booking["segments"]]
        i = booking["segmentIndex"]
        if key == ("SK501", "2026-11-02"):
            problems.append(f"{p.locator}: moved onto the disrupted flight")
        if (origin, destination) != legs[i][:2]:
            problems.append(f"{p.locator}: wrong route")
        earliest = disrupted_departs if i == 0 else max(disrupted_departs, _time(legs[i - 1][3]) + CONNECTION)
        if _time(departs) < earliest:
            problems.append(f"{p.locator}: leaves too early")
        if i + 1 < len(legs) and _time(arrives) > _time(legs[i + 1][2]) - CONNECTION:
            problems.append(f"{p.locator}: misses its next flight")
        seats_used[key] += booking["passengerCount"]
    for key, used in seats_used.items():
        if used > seats.get(key, 0):
            problems.append(f"{key}: {used} passengers on {seats.get(key, 0)} seats")
    return problems
