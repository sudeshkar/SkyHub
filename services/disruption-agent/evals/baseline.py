"""A rule-based planner that plays the part of the model, using the same tools and the same final answer.

It is the yardstick for the language model (does the model beat first-fit on coverage without breaking a rule?) and it
lets the whole pipeline run on a real SkyHub with no API key. Strategy: bookings in the request's order, each takes the
earliest alternative that fits its window and still has seats, counting what this planner has already assigned.
Cost: one find_alternatives call per booking (the server remembers repeated searches), then O(bookings * alternatives)."""
from __future__ import annotations

import json

from disruption_agent.llm import ToolCall, ToolSpec, Turn


class FirstFitModel:
    def complete(self, messages: list[dict], tools: list[ToolSpec]) -> Turn:
        request = json.loads(messages[1]["content"])
        answered = {m["tool_call_id"]: json.loads(m["content"]) for m in messages if m["role"] == "tool"}
        if not answered:
            return Turn(content=None, tool_calls=[
                ToolCall(id=b["locator"], name="find_alternatives", arguments={
                    "origin": b["origin"], "destination": b["destination"], "not_before": b["earliest_departure"],
                    "passengers": b["passengers"], **({"latest_arrival": b["latest_arrival"]} if b["latest_arrival"] else {})})
                for b in request["bookings"]])
        # a dated flight is its number AND its date: the same flight a day later is a perfectly good alternative
        disrupted = (request["disrupted_flight"]["flight_number"], request["disrupted_flight"]["departure"][:10])
        left: dict[tuple[str, str], int] = {}
        proposals = []
        for booking in request["bookings"]:
            options = answered.get(booking["locator"], {}).get("alternatives", [])
            choice = next((a for a in options if (a["flight_number"], a["departure_date"]) != disrupted
                           and left.setdefault((a["flight_number"], a["departure_date"]), a["seats_available"]) >= booking["passengers"]),
                          None)
            if choice is None:
                proposals.append({"locator": booking["locator"], "action": "NEEDS_HUMAN", "rationale": "No flight fits."})
                continue
            left[(choice["flight_number"], choice["departure_date"])] -= booking["passengers"]
            proposals.append({"locator": booking["locator"], "action": "REBOOK", "flight_number": choice["flight_number"],
                              "departure_date": choice["departure_date"], "rationale": "Earliest flight that fits."})
        return Turn(content=None, tool_calls=[ToolCall(id="done", name="submit_proposals", arguments={"proposals": proposals})])
