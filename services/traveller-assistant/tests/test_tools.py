import pytest

from traveller_assistant.help_centre import Article
from traveller_assistant.index import HelpCentreIndex
from traveller_assistant.passages import passages_of
from traveller_assistant.tools import NOTHING_FOUND, ONLY_FROM_THESE, ToolInputError, search_help_centre
from tests.fakes import WordsEmbedder

INDEX = HelpCentreIndex(
    passages_of(Article("checking-in-online", "Checking in online", "s", (),
                        "## When you can check in\n\nOnline check-in opens before departure.\n\n"
                        "## Boarding passes\n\nA boarding pass has a QR code.\n")),
    WordsEmbedder(), max_distance=0.9)


def test_returns_the_passages_as_plain_data_naming_the_article_each_comes_from():
    answer = search_help_centre(INDEX, "when does online check-in open")

    assert answer["passages"][0] == {"article": "checking-in-online", "title": "Checking in online",
                                     "section": "When you can check in", "text": "Online check-in opens before departure."}


def test_passages_come_with_the_rule_that_the_answer_must_come_from_them_or_be_i_do_not_know():
    # Retrieval cannot tell "baggage" (not in the help centre) from a real question by distance (ADR-0091), so the
    # passages that come back for it are near but do not answer it: the model has to be told to check.
    answer = search_help_centre(INDEX, "when does online check-in open")

    assert answer["note"] == ONLY_FROM_THESE
    assert "only" in ONLY_FROM_THESE and "do not know" in ONLY_FROM_THESE and "article" in ONLY_FROM_THESE


def test_asks_for_three_passages_unless_told_otherwise():
    assert len(search_help_centre(HelpCentreIndex(INDEX.passages, WordsEmbedder(), max_distance=2.0), "check")["passages"]) == 2
    assert len(search_help_centre(INDEX, "online check-in boarding pass", limit=1)["passages"]) == 1


def test_with_nothing_found_tells_the_model_to_say_it_does_not_know():
    answer = search_help_centre(INDEX, "recipe for chocolate cake")

    assert answer == {"passages": [], "note": NOTHING_FOUND}
    assert "do not know" in NOTHING_FOUND and "guess" in NOTHING_FOUND


@pytest.mark.parametrize("question", ["", "   \n "])
def test_refuses_an_empty_question(question):
    with pytest.raises(ToolInputError, match="Ask a question"):
        search_help_centre(INDEX, question)


def test_refuses_a_question_over_five_hundred_characters_so_one_call_costs_a_bounded_amount():
    with pytest.raises(ToolInputError, match="at most 500 characters"):
        search_help_centre(INDEX, "x" * 501)


@pytest.mark.parametrize("limit", [0, 6, -1])
def test_refuses_a_number_of_passages_outside_one_to_five(limit):
    with pytest.raises(ToolInputError, match="between 1 and 5"):
        search_help_centre(INDEX, "check-in", limit=limit)


def test_a_question_at_the_limit_is_accepted():
    assert search_help_centre(INDEX, "check-in " + "x" * 491)["passages"] is not None
