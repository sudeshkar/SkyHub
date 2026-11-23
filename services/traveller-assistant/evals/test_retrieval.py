"""Retrieval with the real model must stay at least this good (ADR-0091). ``python -m evals`` shows each question.

Measured on 2026-11-23 at the 0.70 cut-off: hit@1 92%, hit@3 100%, off topic returned nothing 100%, not covered
returned nothing 25%. The bars are a little lower than the measurement, because the model's arithmetic can differ
slightly between machines; a change that costs more than that fails.
"""

import pytest

from evals.retrieval import Report, evaluate
from traveller_assistant.embedding import MiniLmEmbedder
from traveller_assistant.index import DEFAULT_MAX_DISTANCE, build_index

HIT_AT_1 = 0.88
HIT_AT_3 = 0.96


@pytest.fixture(scope="module")
def report() -> Report:
    return evaluate(build_index(MiniLmEmbedder(), max_distance=2.0))


def test_the_right_article_usually_comes_first(report):
    assert report.hit_at(1) >= HIT_AT_1


def test_the_right_article_is_almost_always_in_the_top_three(report):
    misses = [a.question for a in report.answerable if (r := a.rank(DEFAULT_MAX_DISTANCE)) is None or r > 3]

    assert report.hit_at(3) >= HIT_AT_3, misses


def test_a_question_that_has_nothing_to_do_with_skyhub_gets_no_passages(report):
    kept = [u.question for u in report.off_topic if not u.rejected(DEFAULT_MAX_DISTANCE)]

    assert kept == []
