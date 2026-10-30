import json

from tests import world as w
from tests.harness import run_agent
from tests.scripted import FunctionModel, ScriptedModel, call, everything_shown, rebook, say, submit
from tests.world import TOKEN, World

FIND = call("find_alternatives", origin="DXB", destination="LHR", not_before="2026-11-02T08:30:00Z", passengers=1)


def by_locator(report):
    return {p.locator: p for p in report.proposals}


def test_proposes_flights_the_search_really_returned_and_the_guardrails_accept_them():
    model = ScriptedModel(FIND, submit(rebook("ABC234", "SK503"), rebook("DEF567", "SK502")))

    report = run_agent(model)

    assert report.bookings_total == 2
    assert (by_locator(report)["ABC234"].flight_number, by_locator(report)["DEF567"].flight_number) == ("SK503", "SK502")
    assert report.rejections == []


def test_replaces_an_invented_flight_with_a_request_for_a_person_and_says_why():
    model = ScriptedModel(FIND, submit(rebook("ABC234", "SK999"), rebook("DEF567", "SK502")))

    report = run_agent(model)

    assert by_locator(report)["ABC234"].action == "NEEDS_HUMAN"
    assert by_locator(report)["DEF567"].action == "REBOOK"
    assert "SK999" in report.rejections[0].reason


def test_refuses_to_overfill_the_one_seat_flight_even_when_the_model_suggests_it_twice():
    model = ScriptedModel(FIND, submit(rebook("ABC234", "SK502"), rebook("DEF567", "SK502")))

    report = run_agent(model)

    assert by_locator(report)["ABC234"].action == "NEEDS_HUMAN"   # two passengers, one seat
    assert by_locator(report)["DEF567"].action == "REBOOK"


def test_hands_everything_to_a_person_when_the_model_never_submits():
    report = run_agent(ScriptedModel(say("I think they should be moved."), say("Done.")))

    assert {p.action for p in report.proposals} == {"NEEDS_HUMAN"}
    assert all("no proposal" in p.rationale for p in report.proposals)


def test_stops_a_model_that_keeps_calling_tools_and_still_returns_a_safe_answer():
    model = ScriptedModel(*[FIND] * 50)

    report = run_agent(model, max_steps=4)

    assert len(model.seen) == 4
    assert {p.action for p in report.proposals} == {"NEEDS_HUMAN"}


def test_shows_the_model_a_tool_error_and_lets_it_carry_on():
    model = ScriptedModel(call("find_alternatives", origin="DXB", destination="LHR", not_before="tomorrow"),
                          FIND, submit(rebook("ABC234", "SK503"), rebook("DEF567", "SK502")))

    report = run_agent(model)

    tool_messages = [m for m in model.seen[1] if m["role"] == "tool"]
    assert "error" in json.loads(tool_messages[0]["content"])
    assert by_locator(report)["ABC234"].action == "REBOOK"


def test_asks_the_model_to_resubmit_when_its_proposals_are_malformed():
    model = ScriptedModel(FIND, submit({"locator": "ABC234", "action": "REBOOK"}),
                          submit(rebook("ABC234", "SK503"), rebook("DEF567", "SK502")))

    report = run_agent(model)

    last_tool = [m for m in model.seen[2] if m["role"] == "tool"][-1]
    assert "error" in json.loads(last_tool["content"])
    assert by_locator(report)["ABC234"].action == "REBOOK"


def test_only_offers_the_model_the_tools_it_needs_and_keeps_the_staff_tool_to_the_code():
    model = ScriptedModel(FIND, submit())

    run_agent(model)

    offered = {t.name for t in model.tools_offered[0]}
    assert offered == {"find_alternatives", "submit_proposals"}


def test_never_shows_the_model_a_credential_or_anything_that_looks_like_an_email_address():
    model = ScriptedModel(FIND, submit(rebook("ABC234", "SK503"), rebook("DEF567", "SK502")))

    run_agent(model)

    shown = everything_shown(model)
    assert TOKEN not in shown and "@" not in shown and "agent.ops" not in shown


def test_gives_the_model_a_bounded_batch_at_a_time_however_large_the_flight_is():
    original = list(w.BOOKINGS)
    w.BOOKINGS.extend({"locator": f"B{i:05d}", "passengerCount": 1, "segmentIndex": 0,
                       "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]} for i in range(100))

    def decide(messages):
        if not any(m["role"] == "tool" for m in messages):
            return FIND
        mine = json.loads(messages[1]["content"])["bookings"]
        return submit(*[rebook(b["locator"], "SK503") for b in mine])

    model = FunctionModel(decide)
    try:
        report = run_agent(model, batch_size=40)
    finally:
        w.BOOKINGS[:] = original

    prompts = [m for convo in model.seen for m in convo if m["role"] == "user"]
    batch_sizes = sorted({len(json.loads(p["content"])["bookings"]) for p in prompts})
    assert batch_sizes == [22, 40]
    assert report.bookings_total == 102
    # every booking gets an answer; only DEF567 is refused, because SK503 lands after its onward flight
    assert [r.locator for r in report.rejections] == ["DEF567"]
    assert sum(p.action == "REBOOK" for p in report.proposals) == 101


def test_tells_the_model_how_many_seats_earlier_batches_already_took():
    original = list(w.BOOKINGS)
    w.BOOKINGS[:] = [{"locator": f"B{i:05d}", "passengerCount": 1, "segmentIndex": 0,
                      "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]} for i in range(60)]

    def decide(messages):
        if not any(m["role"] == "tool" for m in messages):
            return FIND
        mine = json.loads(messages[1]["content"])["bookings"]
        return submit(*[rebook(b["locator"], "SK503") for b in mine])

    model = FunctionModel(decide)
    try:
        run_agent(model, World(seat_override={("SK503", "2026-11-02"): 100}), batch_size=40)
    finally:
        w.BOOKINGS[:] = original

    def sk503_seats(conversation):
        tool = next(m for m in conversation if m["role"] == "tool")
        return next(a["seats_available"] for a in json.loads(tool["content"])["alternatives"] if a["flight_number"] == "SK503")

    conversations = [c for c in model.seen if sum(m["role"] == "tool" for m in c) == 1 and c[-1]["role"] == "tool"]
    assert [sk503_seats(c) for c in conversations] == [100, 60]     # the second batch sees what the first one used


def test_keeps_counting_seats_across_batches_so_a_big_flight_cannot_overfill_an_alternative():
    original = list(w.BOOKINGS)
    w.BOOKINGS[:] = [{"locator": f"B{i:05d}", "passengerCount": 1, "segmentIndex": 0,
                      "segments": [{"flightNumber": "SK501", "departureDate": "2026-11-02"}]} for i in range(100)]

    def decide(messages):
        if not any(m["role"] == "tool" for m in messages):
            return FIND
        mine = json.loads(messages[1]["content"])["bookings"]
        return submit(*[rebook(b["locator"], "SK503") for b in mine])

    try:
        report = run_agent(FunctionModel(decide), World(seat_override={("SK503", "2026-11-02"): 60}), batch_size=40)
    finally:
        w.BOOKINGS[:] = original

    actions = [p.action for p in report.proposals]
    assert actions.count("REBOOK") == 60 and actions.count("NEEDS_HUMAN") == 40


def test_reports_a_booking_service_outage_as_an_error_instead_of_guessing():
    import pytest
    from disruption_agent.clients import ServiceError

    with pytest.raises(ServiceError):
        run_agent(ScriptedModel(), World(bookings_status=500))


def test_an_empty_flight_needs_no_model_at_all():
    original = list(w.BOOKINGS)
    w.BOOKINGS[:] = []
    model = ScriptedModel()
    try:
        report = run_agent(model)
    finally:
        w.BOOKINGS[:] = original

    assert report.bookings_total == 0 and report.proposals == [] and model.seen == []
