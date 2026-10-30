import time
from datetime import date

from disruption_agent.guardrails import Facts, GuardrailPipeline, Rule, SeatAwareResults, SeatLedger, default_policy, default_rules
from disruption_agent.models import Proposal
from tests.builders import DISRUPTED, alternative, booking, utc


def rebook(locator: str, flight: str, day: date = date(2026, 11, 2), why: str = "earliest flight that fits") -> Proposal:
    return Proposal(locator=locator, action="REBOOK", flight_number=flight, departure_date=day, rationale=why)


def facts(*bookings, alternatives=()):
    f = Facts(disrupted=DISRUPTED)
    f.add_bookings(bookings)
    f.add_alternatives(alternatives)
    return f


def verify(proposals, bookings, known, rules=None):
    pipeline = GuardrailPipeline(known, rules or default_rules())
    return pipeline.verify(proposals, bookings)


def outcome(result, locator):
    return next(p for p in result.proposals if p.locator == locator)


def test_accepts_a_proposal_that_fits_every_rule():
    b = booking("ABC234", 2)
    known = facts(b, alternatives=[alternative("SK503", utc(2, 10, 30))])

    result = verify([rebook("ABC234", "SK503")], [b], known)

    assert outcome(result, "ABC234").action == "REBOOK"
    assert result.rejections == []


def test_never_accepts_a_flight_no_tool_returned():
    b = booking("ABC234")
    known = facts(b, alternatives=[alternative("SK503", utc(2, 10, 30))])

    result = verify([rebook("ABC234", "SK999")], [b], known)

    assert outcome(result, "ABC234").action == "NEEDS_HUMAN"
    assert "SK999" in result.rejections[0].reason and "never" in result.rejections[0].reason


def test_never_moves_a_booking_onto_the_flight_that_was_disrupted():
    b = booking("ABC234")
    known = facts(b, alternatives=[alternative("SK501", utc(2, 8, 30))])

    result = verify([rebook("ABC234", "SK501")], [b], known)

    assert outcome(result, "ABC234").action == "NEEDS_HUMAN"
    assert "disrupted flight" in result.rejections[0].reason


def test_refuses_a_flight_on_a_different_route():
    b = booking("ABC234")
    known = facts(b, alternatives=[alternative("SK610", utc(2, 10), destination="JFK")])

    result = verify([rebook("ABC234", "SK610")], [b], known)

    assert "route" in result.rejections[0].reason


def test_refuses_a_flight_that_leaves_before_the_traveller_can_or_lands_after_the_next_leg():
    early = booking("EARLY1", earliest=utc(2, 10))
    late = booking("LATE11", latest=utc(2, 18))
    known = facts(early, late, alternatives=[alternative("SK502", utc(2, 9)), alternative("SK503", utc(2, 10, 30))])

    result = verify([rebook("EARLY1", "SK502"), rebook("LATE11", "SK503")], [early, late], known)

    reasons = {r.locator: r.reason for r in result.rejections}
    assert "leaves before" in reasons["EARLY1"]
    assert "lands after" in reasons["LATE11"]


def test_never_puts_more_passengers_on_a_flight_than_it_has_seats_and_keeps_count_across_proposals():
    a, b, c = booking("AAA111", 2), booking("BBB222", 1), booking("CCC333", 1)
    known = facts(a, b, c, alternatives=[alternative("SK502", utc(2, 9), seats=3)])

    result = verify([rebook("AAA111", "SK502"), rebook("BBB222", "SK502"), rebook("CCC333", "SK502")], [a, b, c], known)

    assert [outcome(result, x).action for x in ("AAA111", "BBB222", "CCC333")] == ["REBOOK", "REBOOK", "NEEDS_HUMAN"]
    assert "seats" in result.rejections[0].reason


def test_keeps_counting_seats_across_separate_batches_of_one_run():
    first, second = booking("AAA111", 2), booking("BBB222", 2)
    known = facts(first, second, alternatives=[alternative("SK502", utc(2, 9), seats=3)])
    pipeline = GuardrailPipeline(known, default_rules())

    one = pipeline.verify([rebook("AAA111", "SK502")], [first])
    two = pipeline.verify([rebook("BBB222", "SK502")], [second])

    assert outcome(one, "AAA111").action == "REBOOK"
    assert outcome(two, "BBB222").action == "NEEDS_HUMAN"


def test_a_refused_proposal_does_not_use_up_seats():
    big, small = booking("BIG111", 5), booking("SML222", 1)
    known = facts(big, small, alternatives=[alternative("SK502", utc(2, 9), seats=2)])

    result = verify([rebook("BIG111", "SK502"), rebook("SML222", "SK502")], [big, small], known)

    assert [outcome(result, x).action for x in ("BIG111", "SML222")] == ["NEEDS_HUMAN", "REBOOK"]


def test_ignores_a_second_proposal_for_the_same_booking_and_unknown_bookings():
    b = booking("ABC234")
    known = facts(b, alternatives=[alternative("SK502", utc(2, 9)), alternative("SK503", utc(2, 10, 30))])

    result = verify([rebook("ABC234", "SK502"), rebook("ABC234", "SK503"), rebook("ZZZ999", "SK503")], [b], known)

    assert [p.flight_number for p in result.proposals] == ["SK502"]
    assert {r.locator for r in result.rejections} == {"ABC234", "ZZZ999"}


def test_hands_every_booking_with_no_proposal_to_a_person():
    a, b = booking("AAA111"), booking("BBB222")
    known = facts(a, b, alternatives=[alternative("SK502", utc(2, 9))])

    result = verify([rebook("AAA111", "SK502")], [a, b], known)

    assert outcome(result, "BBB222").action == "NEEDS_HUMAN"
    assert "no proposal" in outcome(result, "BBB222").rationale


def test_passes_through_a_proposal_that_asks_for_a_person():
    b = booking("ABC234")
    asked = Proposal(locator="ABC234", action="NEEDS_HUMAN", rationale="no flight fits")

    result = verify([asked], [b], facts(b))

    assert result.proposals == [asked] and result.rejections == []


def test_trims_and_bounds_the_rationale():
    p = Proposal(locator="ABC234", action="NEEDS_HUMAN", rationale="  " + "x" * 5000 + "  ")

    assert len(p.rationale) <= 300 and not p.rationale.startswith(" ")


def test_the_ledger_counts_seats_already_given_out():
    ledger, flight = SeatLedger(), alternative("SK502", utc(2, 9), seats=10)

    assert ledger.remaining(flight) == 10
    ledger.take(flight, 4)
    ledger.take(flight, 3)

    assert ledger.remaining(flight) == 3


def test_shows_the_model_only_the_seats_that_are_still_free_in_this_run():
    ledger = SeatLedger()
    ledger.take(alternative("SK503", utc(2, 10, 30), seats=100), 98)
    shown = SeatAwareResults(ledger).adapt("find_alternatives", {"passengers": 3}, {"alternatives": [
        {"flight_number": "SK503", "departure_date": "2026-11-02", "seats_available": 100},
        {"flight_number": "SK505", "departure_date": "2026-11-02", "seats_available": 150}]})

    # SK503 has 2 left: not enough for 3, so it is not offered; SK505 is untouched
    assert [a["flight_number"] for a in shown["alternatives"]] == ["SK505"]
    assert SeatAwareResults(ledger).adapt("find_alternatives", {"passengers": 1}, {"alternatives": [
        {"flight_number": "SK503", "departure_date": "2026-11-02", "seats_available": 100}]})["alternatives"][0]["seats_available"] == 2


def test_leaves_other_tools_results_and_errors_alone():
    adapter = SeatAwareResults(SeatLedger())

    assert adapter.adapt("get_disruption_impact", {}, {"bookings": []}) == {"bookings": []}
    assert adapter.adapt("find_alternatives", {}, {"error": "bad"}) == {"error": "bad"}


def test_the_default_policy_shares_one_ledger_between_the_capacity_rule_and_what_the_model_is_shown():
    policy = default_policy()
    b = booking("AAA111", 5)
    known = facts(b, alternatives=[alternative("SK502", utc(2, 9), seats=8)])

    GuardrailPipeline(known, policy.rules).verify([rebook("AAA111", "SK502")], [b])
    shown = policy.adapters[0].adapt("find_alternatives", {"passengers": 1}, {"alternatives": [
        {"flight_number": "SK502", "departure_date": "2026-11-02", "seats_available": 8}]})

    assert shown["alternatives"][0]["seats_available"] == 3


def test_a_new_rule_is_added_without_touching_the_existing_ones():
    class NoNextDay(Rule):
        def check(self, proposal, booking, alternative, facts):
            return "next-day flights need approval" if alternative.departure_date != date(2026, 11, 2) else None

    b = booking("ABC234")
    known = facts(b, alternatives=[alternative("SK501", utc(3, 8, 30))])

    result = verify([rebook("ABC234", "SK501", date(2026, 11, 3))], [b], known, rules=[*default_rules(), NoNextDay()])

    assert result.rejections[0].reason == "next-day flights need approval"


def test_verifies_a_huge_number_of_proposals_in_linear_time():
    n = 20_000
    bookings = [booking(f"B{i:05d}") for i in range(n)]
    known = facts(*bookings, alternatives=[alternative("SK503", utc(2, 10, 30), seats=n)])
    proposals = [rebook(b.locator, "SK503") for b in bookings]

    started = time.perf_counter()
    result = verify(proposals, bookings, known)
    elapsed = time.perf_counter() - started

    assert len(result.proposals) == n and all(p.action == "REBOOK" for p in result.proposals)
    assert elapsed < 3.0, f"{n} proposals took {elapsed:.2f}s"
