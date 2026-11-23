import pytest

from traveller_assistant.help_centre import HelpCentreError, load_articles, parse_article

GOOD = """---
id: check-in
title: Checking in online
summary: "When check-in opens and closes."
facts: [check-in-opens]
---
Online check-in opens 24 hours before departure.
"""


def write(directory, name, text):
    (directory / name).write_text(text, encoding="utf-8")


def test_reads_the_header_and_the_body_of_an_article():
    article = parse_article(GOOD, "check-in")

    assert article.id == "check-in"
    assert article.title == "Checking in online"
    assert article.summary == "When check-in opens and closes."
    assert article.facts == ("check-in-opens",)
    assert article.body.strip() == "Online check-in opens 24 hours before departure."


def test_reads_an_article_saved_with_windows_line_endings():
    article = parse_article(GOOD.replace("\n", "\r\n"), "check-in")

    assert article.title == "Checking in online"
    assert "24 hours" in article.body


def test_an_article_that_states_no_fact_may_leave_the_list_out():
    article = parse_article(GOOD.replace("facts: [check-in-opens]\n", ""), "check-in")

    assert article.facts == ()


def test_refuses_an_article_without_a_header():
    with pytest.raises(HelpCentreError, match="check-in: no header"):
        parse_article("Online check-in opens 24 hours before departure.\n", "check-in")


@pytest.mark.parametrize("field", ["title", "summary"])
def test_refuses_an_article_missing_a_title_or_a_summary_and_says_which(field):
    text = "\n".join(line for line in GOOD.splitlines() if not line.startswith(field + ":"))

    with pytest.raises(HelpCentreError, match=f"check-in: '{field}' is missing"):
        parse_article(text, "check-in")


def test_refuses_an_unknown_header_field_so_a_typo_cannot_switch_the_check_off():
    with pytest.raises(HelpCentreError, match="check-in: unknown header field 'fact'"):
        parse_article(GOOD.replace("facts:", "fact:"), "check-in")


def test_refuses_an_id_that_is_not_the_file_name():
    with pytest.raises(HelpCentreError, match="boarding: the id is 'check-in'"):
        parse_article(GOOD, "boarding")


def test_refuses_an_article_with_no_body():
    with pytest.raises(HelpCentreError, match="check-in: the article is empty"):
        parse_article(GOOD.split("---\n", 2)[0] + "---\n" + GOOD.split("---\n", 2)[1] + "---\n\n", "check-in")


def test_refuses_facts_that_are_not_a_list_of_names():
    with pytest.raises(HelpCentreError, match="check-in: 'facts' must be a list of fact names"):
        parse_article(GOOD.replace("[check-in-opens]", "check-in-opens"), "check-in")


def test_loads_every_article_in_a_folder_in_name_order_and_ignores_other_files(tmp_path):
    write(tmp_path, "check-in.md", GOOD)
    write(tmp_path, "boarding.md", GOOD.replace("id: check-in", "id: boarding"))
    write(tmp_path, "facts.yml", "not: an article\n")

    assert [a.id for a in load_articles(tmp_path)] == ["boarding", "check-in"]


def test_refuses_a_folder_with_no_articles(tmp_path):
    with pytest.raises(HelpCentreError, match="no articles"):
        load_articles(tmp_path)
