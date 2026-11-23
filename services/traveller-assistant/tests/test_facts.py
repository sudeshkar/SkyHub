import pytest

from traveller_assistant.facts import Fact, FactError, load_facts, unbacked_numbers

FACTS_YML = r"""
check-in-opens:
  value: 24
  says: "{value} hours before"
  source: CheckInConfig.java
  pattern: 'check-in\.opens-before:PT(\d+)H\}'
seat-hold:
  value: 10
  says: "{value} minutes"
  source: HoldService.java
  pattern: 'hold-ttl:PT(\d+)M\}'
"""


def fact(**changes):
    fields = dict(id="check-in-opens", value=24, says="{value} hours before", source="CheckInConfig.java",
                  pattern=r"check-in\.opens-before:PT(\d+)H\}")
    fields.update(changes)
    return Fact(**fields)


def write(directory, name, text):
    path = directory / name
    path.write_text(text, encoding="utf-8")
    return path


def test_says_a_fact_in_its_registered_words():
    assert fact().phrase == "24 hours before"


def test_reads_the_value_the_code_enforces(tmp_path):
    write(tmp_path, "CheckInConfig.java", '@Value("${skyhub.booking.check-in.opens-before:PT24H}") Duration opensBefore')

    assert fact().value_in_code(tmp_path) == 24


def test_reads_a_value_from_a_file_with_windows_line_endings(tmp_path):
    write(tmp_path, "application.yml", "boarding:\r\n  opens-before: PT45M\r\n  closes-before: PT10M\r\n")
    closes = fact(value=10, source="application.yml",
                  pattern=r"boarding:\s*\n\s*opens-before: PT\d+M\s*\n\s*closes-before: PT(\d+)M")

    assert closes.value_in_code(tmp_path) == 10


def test_a_source_file_that_is_gone_is_an_error_that_names_the_fact(tmp_path):
    with pytest.raises(FactError, match="check-in-opens: CheckInConfig.java does not exist"):
        fact().value_in_code(tmp_path)


def test_a_pattern_that_no_longer_matches_is_an_error(tmp_path):
    write(tmp_path, "CheckInConfig.java", "Duration opensBefore = Duration.ofHours(24);")

    with pytest.raises(FactError, match="check-in-opens: expected the pattern once in CheckInConfig.java, found 0"):
        fact().value_in_code(tmp_path)


def test_a_pattern_that_matches_twice_is_an_error_because_the_value_is_no_longer_in_one_place(tmp_path):
    write(tmp_path, "CheckInConfig.java", "check-in.opens-before:PT24H}\ncheck-in.opens-before:PT48H}\n")

    with pytest.raises(FactError, match="found 2"):
        fact().value_in_code(tmp_path)


def test_loads_the_registry_keyed_by_fact_name(tmp_path):
    facts = load_facts(write(tmp_path, "facts.yml", FACTS_YML))

    assert list(facts) == ["check-in-opens", "seat-hold"]
    assert facts["seat-hold"] == Fact("seat-hold", 10, "{value} minutes", "HoldService.java", r"hold-ttl:PT(\d+)M\}")


@pytest.mark.parametrize("broken, message", [
    ("value: 24", "check-in-opens: 'value' must be a whole number"),
    ('says: "{value} hours before"', "check-in-opens: 'says' must contain {value}"),
    ("pattern: 'check-in\\.opens-before:PT(\\d+)H\\}'", "check-in-opens: the pattern must have exactly one group"),
])
def test_refuses_a_registry_entry_that_could_not_be_checked(tmp_path, broken, message):
    replacement = {"value: 24": "value: twenty-four",
                   'says: "{value} hours before"': 'says: "24 hours before"',
                   "pattern: 'check-in\\.opens-before:PT(\\d+)H\\}'": "pattern: 'check-in\\.opens-before:PT\\d+H'"}[broken]

    with pytest.raises(FactError, match=message.replace("{", r"\{").replace("}", r"\}")):
        load_facts(write(tmp_path, "facts.yml", FACTS_YML.replace(broken, replacement)))


def test_refuses_a_registry_entry_missing_a_field(tmp_path):
    with pytest.raises(FactError, match="seat-hold: 'source' is missing"):
        load_facts(write(tmp_path, "facts.yml", FACTS_YML.replace("  source: HoldService.java\n", "")))


def test_finds_no_unbacked_number_when_every_number_is_part_of_a_declared_fact():
    text = "Check-in opens 24 hours before departure. Seats are held for 10 minutes."

    assert unbacked_numbers(text, [fact(), fact(id="seat-hold", value=10, says="{value} minutes")]) == []


def test_finds_a_number_the_article_states_without_declaring_where_the_code_says_it():
    text = "Check-in opens 24 hours before departure. You can cancel up to 48 hours before."

    assert unbacked_numbers(text, [fact()]) == ["48"]


def test_a_number_inside_a_fact_that_the_article_did_not_declare_is_still_unbacked():
    assert unbacked_numbers("Seats are held for 10 minutes.", [fact()]) == ["10"]


def test_a_declared_value_written_in_other_words_is_unbacked():
    assert unbacked_numbers("Check-in opens 24 hours ahead.", [fact()]) == ["24"]
