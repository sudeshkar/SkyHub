"""The evals as tests: they run offline, need no key, and fail the build if a guarantee is ever lost."""
import random

import pytest

from disruption_agent.firstfit import FirstFitPlanner
from evals.chaos import ChaosModel
from evals.oracle import violations
from evals.scenarios import installed, random_world, standard
from tests.harness import run_agent


@pytest.mark.parametrize("scenario", standard(), ids=lambda s: s.name)
def test_the_first_fit_planner_gives_a_safe_answer_in_every_scenario(scenario):
    with installed(scenario) as world:
        report = run_agent(FirstFitPlanner(), world)

    assert violations(report, scenario.bookings, world.seats) == []
    assert report.rejections == []        # a sound planner is never refused


def test_when_nothing_flies_every_booking_goes_to_a_person():
    scenario = next(s for s in standard() if s.name == "nothing flies")
    with installed(scenario) as world:
        report = run_agent(FirstFitPlanner(), world)

    assert {p.action for p in report.proposals} == {"NEEDS_HUMAN"}


def test_a_fully_booked_a380_is_handled_in_batches_and_never_overfills_a_flight():
    scenario = next(s for s in standard() if s.name == "a fully booked A380")
    with installed(scenario) as world:
        report = run_agent(FirstFitPlanner(), world, batch_size=40)

    assert violations(report, scenario.bookings, world.seats) == []
    assert report.bookings_total == 500 and report.rebooked > 0


def test_whatever_a_chaotic_model_says_the_answer_a_person_sees_is_safe():
    """200 random worlds, each judged against a deliberately bad model: wrong flights, invented flights, the disrupted
    flight, duplicates, forgotten and made-up bookings. The guardrails must still produce a safe answer every time."""
    refused = 0
    for seed in range(200):
        rng = random.Random(seed)
        scenario = random_world(rng)
        with installed(scenario) as world:
            report = run_agent(ChaosModel(rng), world, batch_size=rng.choice([5, 40]))
        assert violations(report, scenario.bookings, world.seats) == [], f"seed {seed}"
        refused += len(report.rejections)

    assert refused > 500, "the chaos was too tame to prove anything"
