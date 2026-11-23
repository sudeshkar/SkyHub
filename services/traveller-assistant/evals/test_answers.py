"""The whole assistant with the real model must stay at least this good (ADR-0093). ``python -m evals.answers`` shows
each question.

Opt-in: these spend Groq's free tier (about 50,000 tokens, several minutes), so they run only with
SKYHUB_LIVE_EVALS=1 and a GROQ_API_KEY (in the environment or the repository's .env). CI runs neither.

Measured on 2026-11-23 with openai/gpt-oss-120b, two runs: help 6 of 6, not covered 5 of 5 declined, off topic 3 of 3,
flights 2 of 2, no answer replaced for naming a flight the search never returned.
"""

import os

import pytest

from evals.answers import Report, evaluate, groq_key, live_assistant

pytestmark = pytest.mark.skipif(not (os.environ.get("SKYHUB_LIVE_EVALS") == "1" and groq_key()),
                                reason="live answer evals: set SKYHUB_LIVE_EVALS=1 and GROQ_API_KEY")

HELP = 0.8
FLIGHTS = 0.5


@pytest.fixture(scope="module")
def report() -> Report:
    with live_assistant(groq_key()) as assistant:
        return evaluate(assistant)


def test_every_question_the_help_centre_does_not_cover_gets_i_do_not_know(report):
    assert report.share("not_covered") == 1.0, [r.question for r in report.results
                                                 if r.group == "not_covered" and not r.passed]


def test_every_message_not_about_skyhub_is_answered_without_a_crew(report):
    assert report.share("off_topic") == 1.0


def test_most_help_questions_are_answered_with_the_right_article_and_fact(report):
    assert report.share("help") >= HELP, [(r.question, r.why) for r in report.results if r.group == "help" and not r.passed]


def test_flight_questions_are_answered_with_flights_the_search_returned(report):
    assert report.share("flights") >= FLIGHTS
