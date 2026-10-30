"""The agent's vocabulary. Immutable values, validated on creation, so bad data fails at the edge and nowhere deeper."""
from __future__ import annotations

from datetime import date, datetime, timezone
from typing import Literal

from pydantic import BaseModel, ConfigDict, field_validator, model_validator

MAX_RATIONALE = 300


class Leg(BaseModel):
    """One scheduled flight. Times are UTC, like everywhere in SkyHub (ADR-0005)."""

    model_config = ConfigDict(frozen=True)

    flight_number: str
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    fare: float
    currency: str

    @property
    def departure_date(self) -> date:
        """The key SkyHub uses for a dated flight: its scheduled UTC departure date."""
        return self.departure.astimezone(timezone.utc).date()

    @property
    def key(self) -> tuple[str, date]:
        return self.flight_number, self.departure_date

    @classmethod
    def from_api(cls, body: dict) -> "Leg":
        return cls(flight_number=body["flightNumber"], origin=body["origin"], destination=body["destination"],
                   departure=body["departureTime"], arrival=body["arrivalTime"], fare=body["fare"], currency=body["currency"])


class Alternative(Leg):
    """A flight a disrupted booking could move to, with how many seats are free on it right now."""

    seats_available: int


class AffectedBooking(BaseModel):
    """A booking on the disrupted flight and the window it can be moved within. No names, no contact details."""

    model_config = ConfigDict(frozen=True)

    locator: str
    passenger_count: int
    disrupted_segment_index: int
    segments: tuple[Leg, ...]
    earliest_departure: datetime
    latest_arrival: datetime | None = None

    @property
    def disrupted_leg(self) -> Leg:
        return self.segments[self.disrupted_segment_index]


class Proposal(BaseModel):
    """What the agent suggests for one booking. Never carried out by the agent: a person approves it."""

    model_config = ConfigDict(frozen=True)

    locator: str
    action: Literal["REBOOK", "NEEDS_HUMAN"]
    flight_number: str | None = None
    departure_date: date | None = None
    rationale: str = ""
    # Which leg of the booking to move. Filled in by the guardrails from the booking, never taken from the model.
    segment_index: int | None = None

    @field_validator("rationale", mode="before")
    @classmethod
    def _tidy(cls, value: object) -> str:
        return str(value or "").strip()[:MAX_RATIONALE]

    @model_validator(mode="after")
    def _a_rebooking_names_its_flight(self) -> "Proposal":
        if self.action == "REBOOK" and (not self.flight_number or self.departure_date is None):
            raise ValueError("a REBOOK proposal needs flight_number and departure_date")
        return self

    @classmethod
    def needs_human(cls, locator: str, why: str) -> "Proposal":
        return cls(locator=locator, action="NEEDS_HUMAN", rationale=why)


class Rejection(BaseModel):
    """A suggestion the guardrails refused, and why. Kept so the model's quality can be measured."""

    model_config = ConfigDict(frozen=True)

    locator: str
    reason: str


class Report(BaseModel):
    flight_number: str
    departure_date: date
    bookings_total: int
    proposals: list[Proposal]
    rejections: list[Rejection] = []

    @property
    def rebooked(self) -> int:
        return sum(1 for p in self.proposals if p.action == "REBOOK")

    def to_text(self) -> str:
        lines = [f"Disruption on {self.flight_number} {self.departure_date}: {self.bookings_total} affected booking(s), "
                 f"{self.rebooked} proposed for rebooking, {self.bookings_total - self.rebooked} for a person to decide."]
        for p in self.proposals:
            target = f"{p.flight_number} on {p.departure_date}" if p.action == "REBOOK" else "needs a person"
            lines.append(f"  {p.locator}: {target}. {p.rationale}")
        if self.rejections:
            lines.append(f"{len(self.rejections)} suggestion(s) were refused by the guardrails:")
            lines.extend(f"  {r.locator}: {r.reason}" for r in self.rejections)
        return "\n".join(lines)
