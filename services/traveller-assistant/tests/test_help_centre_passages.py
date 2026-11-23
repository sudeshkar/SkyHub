"""The real help centre cut into passages (ADR-0091): every passage fits what the embedding model reads."""

import pytest

from traveller_assistant.help_centre import load_articles
from traveller_assistant.index import HELP_CENTRE
from traveller_assistant.passages import MAX_WORDS, passages_of

ARTICLES = load_articles(HELP_CENTRE)
PASSAGES = [p for a in ARTICLES for p in passages_of(a)]


@pytest.mark.parametrize("passage", PASSAGES, ids=lambda p: p.id)
def test_a_passage_is_short_enough_for_the_model_to_read_all_of_it(passage):
    assert passage.words <= MAX_WORDS, (
        f"{passage.id} is {passage.words} words; the model reads about {MAX_WORDS}. Split the section with a new heading")


def test_every_passage_in_the_help_centre_has_its_own_id():
    ids = [p.id for p in PASSAGES]

    assert len(ids) == len(set(ids))


@pytest.mark.parametrize("article", ARTICLES, ids=lambda a: a.id)
def test_every_article_can_be_found(article):
    assert any(p.article_id == article.id for p in PASSAGES)
