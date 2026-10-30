from datetime import date

from disruption_agent.apply import Applier, ConsoleApprover, Decision
from disruption_agent.clients import BookingClient
from disruption_agent.models import Proposal, Report
from tests.world import BOOKING_URL, World


def report(*proposals: Proposal) -> Report:
    return Report(flight_number="SK501", departure_date=date(2026, 11, 2), bookings_total=len(proposals), proposals=list(proposals))


def rebook(locator: str, flight: str = "SK503", segment: int = 0) -> Proposal:
    return Proposal(locator=locator, action="REBOOK", flight_number=flight, departure_date=date(2026, 11, 2),
                    rationale="earliest flight that fits", segment_index=segment)


def human(locator: str) -> Proposal:
    return Proposal.needs_human(locator, "no flight fits")


class Approving:
    """Answers each question from a list, and remembers what it was asked."""

    def __init__(self, *answers: Decision):
        self.answers = list(answers)
        self.asked: list[str] = []

    def approve(self, proposal: Proposal) -> Decision:
        self.asked.append(proposal.locator)
        return self.answers.pop(0)


def applier(world: World, approver) -> Applier:
    return Applier(BookingClient(world.client(BOOKING_URL), "agent.ops", "pw"), approver)


def test_moves_only_the_bookings_a_person_approved():
    world, approver = World(), Approving(Decision.YES, Decision.NO)

    outcomes = applier(world, approver).apply(report(rebook("AAA111"), rebook("BBB222")))

    assert [o.status for o in outcomes] == ["moved", "declined"]
    assert [r["locator"] for r in world.rebookings] == ["AAA111"]


def test_never_calls_the_booking_service_without_an_answer_from_a_person():
    world, approver = World(), Approving(Decision.NO, Decision.NO)

    applier(world, approver).apply(report(rebook("AAA111"), rebook("BBB222")))

    assert world.rebookings == []


def test_sends_the_leg_and_flight_the_guardrails_verified():
    world = World()

    applier(world, Approving(Decision.YES)).apply(report(rebook("DEF567", "SK502", segment=1)))

    assert world.rebookings == [{"locator": "DEF567", "segmentIndex": 1, "flightNumber": "SK502", "departureDate": "2026-11-02"}]


def test_does_not_ask_about_bookings_that_need_a_person_to_decide_anyway():
    world, approver = World(), Approving(Decision.YES)

    outcomes = applier(world, approver).apply(report(human("AAA111"), rebook("BBB222")))

    assert approver.asked == ["BBB222"]
    assert [(o.locator, o.status) for o in outcomes] == [("AAA111", "skipped"), ("BBB222", "moved")]


def test_all_approves_the_rest_without_asking_again_and_quit_stops_at_once():
    world, approver = World(), Approving(Decision.ALL)
    applier(world, approver).apply(report(rebook("AAA111"), rebook("BBB222"), rebook("CCC333")))
    assert approver.asked == ["AAA111"] and len(world.rebookings) == 3

    world, approver = World(), Approving(Decision.YES, Decision.QUIT)
    outcomes = applier(world, approver).apply(report(rebook("AAA111"), rebook("BBB222"), rebook("CCC333")))
    assert [o.status for o in outcomes] == ["moved", "stopped", "stopped"] and len(world.rebookings) == 1


def test_carries_on_after_a_refused_move_and_reports_why():
    world = World(rebook_responses={"AAA111": (409, {"detail": "only 0 seats left on SK503"})})

    outcomes = applier(world, Approving(Decision.YES, Decision.YES)).apply(report(rebook("AAA111"), rebook("BBB222")))

    assert [o.status for o in outcomes] == ["failed", "moved"]
    assert "0 seats" in outcomes[0].detail


def test_says_when_a_booking_was_already_on_the_flight():
    world = World(rebook_responses={"AAA111": (200, {"locator": "AAA111", "segmentIndex": 0, "changed": False,
                                                    "from": {"flightNumber": "SK503", "departureDate": "2026-11-02"},
                                                    "to": {"flightNumber": "SK503", "departureDate": "2026-11-02"}, "seats": ["14A"]})})

    outcomes = applier(world, Approving(Decision.YES)).apply(report(rebook("AAA111")))

    assert outcomes[0].status == "unchanged"


def test_refuses_a_proposal_that_was_not_verified_by_the_guardrails():
    world, approver = World(), Approving(Decision.YES)
    unverified = Proposal(locator="AAA111", action="REBOOK", flight_number="SK503", departure_date=date(2026, 11, 2), rationale="x")

    outcomes = applier(world, approver).apply(report(unverified))   # no segment_index: it never passed the pipeline

    assert outcomes[0].status == "skipped" and world.rebookings == [] and approver.asked == []


def test_the_console_asks_plainly_and_understands_the_answers():
    answers = iter(["", "maybe", "y", "yes", "N", "a", "q"])
    shown: list[str] = []
    approver = ConsoleApprover(input_fn=lambda prompt: next(answers), output_fn=shown.append)
    p = rebook("ABC234", "SK503")

    decisions = [approver.approve(p) for _ in range(5)]

    assert decisions == [Decision.NO, Decision.YES, Decision.YES, Decision.NO, Decision.ALL]
    assert any("ABC234" in line and "SK503" in line for line in shown)
    assert any("y, n, a or q" in line for line in shown)         # it said what it expected after "maybe"
