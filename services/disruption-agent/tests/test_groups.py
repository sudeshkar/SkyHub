"""Planning by group (ADR-0089): bookings that need the same thing are one line for the model, which chooses the flights;
code fills them, largest parties first, and the guardrails still check every booking."""
import json
from datetime import datetime, timezone

from disruption_agent.groups import group_bookings, pack
from disruption_agent.guardrails import Facts, default_rules, SeatLedger
from disruption_agent.models import AffectedBooking, Alternative, Leg
from disruption_agent.strategies import ByGroup
from tests import world as w
from tests.harness import run_agent
from tests.scripted import FunctionModel, ScriptedModel, call, everything_shown, say
from tests.world import TOKEN, World

UTC = timezone.utc


def leg(number: str, day: int, leaves: str, lands: str, origin: str = "DXB", destination: str = "LHR") -> Leg:
    return Leg(flight_number=number, origin=origin, destination=destination, fare=100.0, currency="USD",
               departure=datetime.fromisoformat(f"2026-11-{day:02d}T{leaves}:00+00:00"),
               arrival=datetime.fromisoformat(f"2026-11-{day:02d}T{lands}:00+00:00"))


DISRUPTED = leg("SK501", 2, "08:30", "16:20")


def booking(locator: str, pax: int, *, latest: datetime | None = None) -> AffectedBooking:
    return AffectedBooking(locator=locator, passenger_count=pax, disrupted_segment_index=0, segments=(DISRUPTED,),
                           earliest_departure=DISRUPTED.departure, latest_arrival=latest)


def alternative(number: str, leaves: str, lands: str, seats: int, day: int = 2) -> Alternative:
    return Alternative(**leg(number, day, leaves, lands).model_dump(), seats_available=seats)


def facts_with(*alternatives: Alternative, bookings=()) -> Facts:
    facts = Facts(("SK501", DISRUPTED.departure_date))
    facts.add_alternatives(alternatives)
    facts.add_bookings(bookings)
    return facts


# ---- grouping -----------------------------------------------------------------------------------------------------------

def test_bookings_with_the_same_route_and_window_are_one_group_and_a_tighter_window_is_another():
    late = datetime(2026, 11, 2, 18, 0, tzinfo=UTC)
    bookings = [booking("A1", 1), booking("A2", 4), booking("C1", 1, latest=late), booking("A3", 2)]

    groups = group_bookings(bookings)

    assert [g.id for g in groups] == ["G1", "G2"]
    assert [b.locator for b in groups[0].bookings] == ["A1", "A2", "A3"]
    assert [b.locator for b in groups[1].bookings] == ["C1"]
    assert (groups[0].passengers, groups[0].party_sizes) == (7, {1: 1, 2: 1, 4: 1})


# ---- packing: families first ----------------------------------------------------------------------------------------------

def test_fills_the_chosen_flights_in_order_with_the_largest_parties_first():
    group = group_bookings([booking("S1", 1), booking("F4", 4), booking("P2", 2)])[0]
    sk503, sk505 = alternative("SK503", "10:30", "18:20", 5), alternative("SK505", "14:00", "21:50", 2)
    facts = facts_with(sk503, sk505, bookings=group.bookings)

    proposals = pack(group, [sk503, sk505], default_rules(), facts, taken={}, why="earliest first")

    placed = {p.locator: (p.action, p.flight_number) for p in proposals}
    assert placed == {"F4": ("REBOOK", "SK503"), "P2": ("REBOOK", "SK505"), "S1": ("REBOOK", "SK503")}


def test_a_family_that_fits_nowhere_goes_to_a_person_rather_than_being_split():
    group = group_bookings([booking("F4", 4), booking("S1", 1)])[0]
    sk503 = alternative("SK503", "10:30", "18:20", 3)
    facts = facts_with(sk503, bookings=group.bookings)

    proposals = {p.locator: p for p in pack(group, [sk503], default_rules(), facts, taken={}, why="")}

    assert proposals["F4"].action == "NEEDS_HUMAN" and "4" in proposals["F4"].rationale
    assert proposals["S1"].flight_number == "SK503"


def test_counts_seats_already_given_to_other_groups_and_earlier_batches():
    group = group_bookings([booking("A1", 2), booking("A2", 2)])[0]
    sk503 = alternative("SK503", "10:30", "18:20", 4)
    ledger = SeatLedger()
    ledger.take(sk503, 1)                            # an earlier batch took one seat (the guardrails know) ...
    taken = {sk503.key: 2}                          # ... and the run's count has it plus one for another group in this batch

    proposals = pack(group, [sk503], default_rules(ledger), facts_with(sk503, bookings=group.bookings), taken=taken, why="")

    assert [p.action for p in proposals] == ["REBOOK", "NEEDS_HUMAN"]     # 2 left: one family of 2, not two
    assert taken[sk503.key] == 4


def test_skips_a_chosen_flight_that_breaks_a_rule_for_the_group_instead_of_putting_anyone_on_it():
    late = datetime(2026, 11, 2, 18, 0, tzinfo=UTC)
    group = group_bookings([booking("C1", 1, latest=late)])[0]
    too_late, fits = alternative("SK505", "14:00", "21:50", 9), alternative("SK502", "09:00", "16:50", 9)

    proposals = pack(group, [too_late, fits], default_rules(), facts_with(too_late, fits, bookings=group.bookings), taken={}, why="")

    assert proposals[0].flight_number == "SK502"


# ---- the agent in group mode ------------------------------------------------------------------------------------------------

FIND_ALL = call("find_alternatives", origin="DXB", destination="LHR", not_before="2026-11-02T08:30:00Z", passengers=1)


def submit_plan(*plans: dict):
    return call("submit_plan", plans=list(plans))


def plan(group: str, *flights: str, day: str = "2026-11-02", action: str = "REBOOK") -> dict:
    return {"group": group, "action": action, "rationale": "earliest first",
            "flights": [{"flight_number": f, "departure_date": day} for f in flights]}


def test_the_model_chooses_flights_per_group_and_every_booking_is_checked_and_placed():
    # G1 is ABC234 (2 people, any time); G2 is DEF567 (must land by 18:00, so only SK502 works for it)
    model = ScriptedModel(FIND_ALL, submit_plan(plan("G1", "SK503"), plan("G2", "SK502")))

    report = run_agent(model, strategy=ByGroup())

    moves = {p.locator: (p.action, p.flight_number) for p in report.proposals}
    assert moves == {"ABC234": ("REBOOK", "SK503"), "DEF567": ("REBOOK", "SK502")}
    assert report.rejections == []
    request = json.loads(model.seen[0][1]["content"])
    assert [g["group"] for g in request["groups"]] == ["G1", "G2"]
    assert "bookings" not in request             # the model sees groups, never a list of booking references


def test_a_whole_flight_of_similar_bookings_is_one_line_and_one_conversation():
    original = list(w.BOOKINGS)
    w.BOOKINGS[:] = [{"locator": f"L{i:04d}", "passengerCount": 1 + i % 4, "segmentIndex": 0,
                      "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]} for i in range(500)]

    def decide(messages):
        if not any(m["role"] == "tool" for m in messages):
            return FIND_ALL
        chosen = plan("G1", "SK503", "SK505")
        chosen["flights"].append({"flight_number": "SK501", "departure_date": "2026-11-03"})   # and the next day's flight
        return submit_plan(chosen)

    model = FunctionModel(decide)
    try:
        report = run_agent(model, World(seat_override={("SK503", "2026-11-02"): 400, ("SK505", "2026-11-02"): 400}),
                           strategy=ByGroup())
    finally:
        w.BOOKINGS[:] = original

    conversations = {json.dumps(c[1]) for c in model.seen}
    assert len(conversations) == 1                                 # 500 bookings, one request to the model
    assert report.bookings_total == 500
    moved = sum(p.passenger_count for p in report.proposals if p.action == "REBOOK")
    assert moved == 950                                            # 400 + 400 + 150 seats, every one filled
    assert report.rejections == []
    left = [p for p in report.proposals if p.action == "NEEDS_HUMAN"]
    assert {p.passenger_count for p in left} <= {1, 2}              # families of 3 and 4 were placed first


def test_a_flight_the_model_invented_is_recorded_as_refused_and_the_real_ones_are_still_used():
    model = ScriptedModel(FIND_ALL, submit_plan(plan("G1", "SK999", "SK503"), plan("G2", "SK502")))

    report = run_agent(model, strategy=ByGroup())

    assert {p.locator: p.flight_number for p in report.proposals}["ABC234"] == "SK503"
    assert any("SK999" in r.reason for r in report.rejections)


def test_hands_every_booking_to_a_person_when_the_model_never_plans():
    report = run_agent(ScriptedModel(say("They should be moved.")), strategy=ByGroup())

    assert {p.action for p in report.proposals} == {"NEEDS_HUMAN"}


def test_a_group_the_model_hands_over_goes_to_a_person_with_its_reason():
    model = ScriptedModel(FIND_ALL, submit_plan(plan("G1", "SK503"),
                                                {"group": "G2", "action": "NEEDS_HUMAN", "rationale": "Too tight a connection."}))

    report = run_agent(model, strategy=ByGroup())

    def123 = {p.locator: p for p in report.proposals}["DEF567"]
    assert def123.action == "NEEDS_HUMAN" and "connection" in def123.rationale


def test_never_shows_the_model_a_credential_a_name_or_an_email_address_in_group_mode():
    model = ScriptedModel(FIND_ALL, submit_plan(plan("G1", "SK503"), plan("G2", "SK502")))

    run_agent(model, strategy=ByGroup())

    shown = everything_shown(model)
    assert TOKEN not in shown and "@" not in shown


def test_groups_are_fed_to_the_model_in_bounded_batches_too():
    # the two example bookings are two groups; with a batch of one group each gets its own conversation
    model = FunctionModel(lambda messages: FIND_ALL if not any(m["role"] == "tool" for m in messages)
                          else submit_plan(*[plan(g["group"], "SK502") for g in json.loads(messages[1]["content"])["groups"]]))

    report = run_agent(model, strategy=ByGroup(), batch_size=1)

    requests = [json.loads(c[1]["content"]) for c in model.seen]
    assert sorted({tuple(g["group"] for g in r["groups"]) for r in requests}) == [("G1",), ("G2",)]
    # SK502 has one seat: ABC234 (2 people) cannot have it, DEF567 (1 person) gets it, counted across the two batches
    assert {p.locator: p.action for p in report.proposals} == {"ABC234": "NEEDS_HUMAN", "DEF567": "REBOOK"}
