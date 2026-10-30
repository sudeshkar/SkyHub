import asyncio

import pytest
from starlette.testclient import TestClient

from disruption_agent.clients import BookingClient, SearchClient
from disruption_agent.firstfit import FirstFitPlanner
from disruption_agent.web import Busy, RunGate, create_app
from tests import world as w
from tests.world import BOOKING_URL, SEARCH_URL, TOKEN, World

API_TOKEN = "an-agent-api-token-of-at-least-32-characters"
BODY = {"flightNumber": "SK501", "departureDate": "2026-11-02"}


def app(world: World | None = None, planners=None, default="first-fit", **options):
    world = world or World()
    search = SearchClient(world.client(SEARCH_URL), world.client(BOOKING_URL))
    booking = BookingClient(world.client(BOOKING_URL), "agent.ops", "pw")
    return create_app(booking, search, API_TOKEN, planners or {"first-fit": FirstFitPlanner}, default, **options)


def post(client: TestClient, body=BODY, token: str | None = API_TOKEN):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    return client.post("/v1/proposals", json=body, headers=headers)


def test_refuses_callers_without_the_right_token_and_does_no_work():
    world = World()
    client = TestClient(app(world))

    assert post(client, token=None).status_code == 401
    assert post(client, token="wrong-" + API_TOKEN).status_code == 401
    assert post(client, token=API_TOKEN[:-1]).status_code == 401
    assert world.requests == []


def test_refuses_a_request_that_makes_no_sense():
    client = TestClient(app())

    assert post(client, {"flightNumber": "../x", "departureDate": "2026-11-02"}).status_code == 400
    assert post(client, {"flightNumber": "SK501", "departureDate": "soon"}).status_code == 400
    assert post(client, {"flightNumber": "SK501"}).status_code == 400
    assert post(client, {**BODY, "planner": "oracle"}).status_code == 400
    assert client.post("/v1/proposals", content="not json", headers={"Authorization": f"Bearer {API_TOKEN}"}).status_code == 400


def test_proposes_moves_with_what_the_screen_needs_and_says_which_planner_decided():
    response = post(TestClient(app()))

    assert response.status_code == 200
    body = response.json()
    assert body["planner"] == "first-fit"
    assert (body["flightNumber"], body["departureDate"], body["bookingsTotal"], body["rebooked"]) == ("SK501", "2026-11-02", 2, 2)
    by_locator = {p["locator"]: p for p in body["proposals"]}
    assert by_locator["ABC234"] == {"locator": "ABC234", "action": "REBOOK", "flightNumber": "SK503", "departureDate": "2026-11-02",
                                    "segmentIndex": 0, "passengerCount": 2, "rationale": "Earliest flight that fits."}
    assert (by_locator["DEF567"]["flightNumber"], by_locator["DEF567"]["segmentIndex"]) == ("SK502", 1)


def test_answers_exactly_as_the_published_contract_says():
    """The website's types are generated from api-specs/disruption-agent-api.yaml: the service must not drift from it."""
    import re
    from pathlib import Path

    spec = Path("../../api-specs/disruption-agent-api.yaml").read_text(encoding="utf-8")

    def required(schema: str) -> set[str]:
        block = spec[spec.index(f"    {schema}:\n"):]
        return set(re.search(r"required: \[(.*?)\]", block).group(1).replace(" ", "").split(","))

    body = post(TestClient(app())).json()

    assert set(body) == required("ProposalSet")
    assert set(body["proposals"][0]) == required("Proposal")


def test_hands_a_booking_nothing_fits_to_a_person_and_carries_no_flight_for_it():
    world = World(seat_override={("SK502", "2026-11-02"): 0, ("SK503", "2026-11-02"): 0, ("SK505", "2026-11-02"): 0,
                                 ("SK501", "2026-11-03"): 0, ("SK501", "2026-11-02"): 0})

    body = post(TestClient(app(world))).json()

    assert {p["action"] for p in body["proposals"]} == {"NEEDS_HUMAN"}
    assert all(p["flightNumber"] is None for p in body["proposals"]) and body["rebooked"] == 0


def test_never_returns_anything_that_identifies_a_traveller_or_a_credential():
    text = post(TestClient(app())).text

    assert "@" not in text and TOKEN not in text and API_TOKEN not in text and "agent.ops" not in text
    assert set(post(TestClient(app())).json()["proposals"][0]) == {
        "locator", "action", "flightNumber", "departureDate", "segmentIndex", "passengerCount", "rationale"}


def test_answers_an_empty_flight_without_asking_a_planner():
    original = list(w.BOOKINGS)
    w.BOOKINGS[:] = []
    try:
        body = post(TestClient(app())).json()
    finally:
        w.BOOKINGS[:] = original

    assert body["bookingsTotal"] == 0 and body["proposals"] == []


def test_reports_a_service_outage_as_a_bad_gateway_with_the_reason():
    response = post(TestClient(app(World(bookings_status=500))))

    assert response.status_code == 502
    assert "booking service answered 500" in response.json()["detail"]


def test_chooses_a_registered_planner_by_name_and_reports_one_that_cannot_start():
    class Broken:
        def __init__(self):
            raise RuntimeError("the model key was refused")

    client = TestClient(app(planners={"first-fit": FirstFitPlanner, "groq": Broken}))

    assert post(client, {**BODY, "planner": "first-fit"}).status_code == 200
    broken = post(client, {**BODY, "planner": "groq"})
    assert broken.status_code == 502 and "groq" in broken.json()["detail"]


def test_a_new_planner_is_added_by_registering_it_with_no_change_to_the_service():
    class AlwaysAPerson:
        def complete(self, messages, tools):
            from disruption_agent.llm import ToolCall, Turn
            import json
            mine = json.loads(messages[1]["content"])["bookings"]
            return Turn(content=None, tool_calls=[ToolCall(id="x", name="submit_proposals", arguments={"proposals": [
                {"locator": b["locator"], "action": "NEEDS_HUMAN", "rationale": "A person decides."} for b in mine]})])

    body = post(TestClient(app(planners={"cautious": AlwaysAPerson}, default="cautious"))).json()

    assert body["planner"] == "cautious" and body["rebooked"] == 0


def test_the_gate_lets_a_few_runs_in_and_turns_the_rest_away_after_a_short_wait():
    async def go():
        gate = RunGate(max_concurrent=2, wait_seconds=0.05)
        async with gate:
            async with gate:
                with pytest.raises(Busy):
                    async with gate:
                        pass
        async with gate:    # a place was freed when the others finished
            return True

    assert asyncio.run(go()) is True


def test_a_busy_service_says_so_with_503():
    client = TestClient(app(max_concurrent=1, wait_seconds=0.01))
    gate = client.app.state.gate
    gate._slots._value = 0   # every place is taken

    response = post(client)

    assert response.status_code == 503 and "busy" in response.json()["detail"].lower()
