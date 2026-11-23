"""The help centre against the real code (ADR-0090): every number an article states is registered with the line of
SkyHub code that enforces it, and that line still says it. A failure names the fact and the file to look at."""

from pathlib import Path

import pytest

from traveller_assistant.facts import load_facts, unbacked_numbers
from traveller_assistant.help_centre import load_articles

ROOT = Path(__file__).resolve().parents[3]
HELP_CENTRE = ROOT / "services" / "traveller-assistant" / "help-centre"
FACTS = load_facts(HELP_CENTRE / "facts.yml")
ARTICLES = load_articles(HELP_CENTRE)


@pytest.mark.parametrize("fact", FACTS.values(), ids=lambda f: f.id)
def test_the_code_still_enforces_what_the_help_centre_says(fact):
    in_code = fact.value_in_code(ROOT)

    assert in_code == fact.value, (
        f"{fact.source} now says {in_code}, the help centre says {fact.value}: change '{fact.id}' in facts.yml "
        f"and reread every article that states it")


@pytest.mark.parametrize("article", ARTICLES, ids=lambda a: a.id)
def test_an_article_declares_only_facts_that_are_registered(article):
    assert [f for f in article.facts if f not in FACTS] == []


@pytest.mark.parametrize("article", ARTICLES, ids=lambda a: a.id)
def test_an_article_states_each_fact_it_declares_in_the_registered_words(article):
    missing = [f"{f}: '{FACTS[f].phrase}'" for f in article.facts if f in FACTS and FACTS[f].phrase not in article.body]

    assert missing == []


@pytest.mark.parametrize("article", ARTICLES, ids=lambda a: a.id)
def test_an_article_states_no_number_that_the_code_does_not_back(article):
    declared = [FACTS[f] for f in article.facts if f in FACTS]

    assert unbacked_numbers("\n".join([article.title, article.summary, article.body]), declared) == []


def test_every_registered_fact_is_stated_by_some_article():
    stated = {f for article in ARTICLES for f in article.facts}

    assert sorted(set(FACTS) - stated) == []
