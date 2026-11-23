import pytest

from traveller_assistant.help_centre import Article, HelpCentreError
from traveller_assistant.passages import passages_of


def article(body, id="checking-in-online", title="Checking in online"):
    return Article(id, title, "A summary.", (), body)


BODY = """# Checking in online

Check-in happens on the Manage booking page.

## When you can check in

Check-in opens before departure.

## Seats after check-in

A checked-in seat is fixed.
"""


def test_makes_one_passage_for_the_text_before_the_first_heading_and_one_for_each_section():
    passages = passages_of(article(BODY))

    assert [p.id for p in passages] == ["checking-in-online#intro", "checking-in-online#when-you-can-check-in",
                                        "checking-in-online#seats-after-check-in"]
    assert [p.heading for p in passages] == ["", "When you can check in", "Seats after check-in"]


def test_a_passage_keeps_its_article_and_its_own_text_only():
    seats = passages_of(article(BODY))[2]

    assert (seats.article_id, seats.title) == ("checking-in-online", "Checking in online")
    assert seats.text == "A checked-in seat is fixed."


def test_the_embedded_text_starts_with_the_title_and_the_heading_so_a_short_section_says_what_it_is_about():
    intro, when, _ = passages_of(article(BODY))

    assert when.embedded_text == "Checking in online. When you can check in.\nCheck-in opens before departure."
    assert intro.embedded_text == "Checking in online.\nCheck-in happens on the Manage booking page."


def test_the_article_title_line_is_not_part_of_any_passage():
    assert all("# Checking in online" not in p.text for p in passages_of(article(BODY)))


def test_an_article_that_starts_with_a_section_has_no_intro_passage():
    passages = passages_of(article("# Checking in online\n\n## When you can check in\n\nIt opens before departure.\n"))

    assert [p.id for p in passages] == ["checking-in-online#when-you-can-check-in"]


def test_a_heading_with_nothing_under_it_makes_no_passage():
    passages = passages_of(article("## Empty\n\n## When you can check in\n\nIt opens before departure.\n"))

    assert [p.heading for p in passages] == ["When you can check in"]


def test_headings_become_ids_in_lower_case_words_joined_by_hyphens():
    passages = passages_of(article("## Do I get my money back?\n\nSometimes.\n", id="cancellations-and-refunds"))

    assert passages[0].id == "cancellations-and-refunds#do-i-get-my-money-back"


def test_refuses_two_sections_with_the_same_heading_because_their_ids_would_collide():
    with pytest.raises(HelpCentreError, match="checking-in-online: two sections are called 'Seats'"):
        passages_of(article("## Seats\n\nOne.\n\n## Seats\n\nTwo.\n"))


def test_counts_the_words_the_model_will_read():
    passage = passages_of(article("## When\n\nCheck-in opens before departure.\n"))[0]

    assert passage.words == len("Checking in online. When.\nCheck-in opens before departure.".split())


def test_reads_an_article_with_windows_line_endings_the_same_way():
    assert passages_of(article(BODY.replace("\n", "\r\n"))) == passages_of(article(BODY))
