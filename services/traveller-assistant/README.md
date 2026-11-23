# Traveller assistant

A chat assistant for SkyHub's travellers: it answers questions about SkyHub's rules from a **help centre**, and later
about the traveller's own booking. It never changes a booking: cancelling, changing a seat and checking in are done by
the traveller on the Manage booking page. Design and the slices it is built in: [ADR-0090](../../docs/adr/0090-traveller-assistant-and-its-help-centre.md).

**Built so far (slice 1 of 6):** the help centre and the test that keeps it true. Retrieval, the MCP server, the CrewAI
crew, the API and the website chat follow, one slice at a time.

## The help centre

`help-centre/` holds one Markdown article per topic, written for travellers. Each starts with a header:

```markdown
---
id: checking-in-online            # the file name
title: Checking in online
summary: "When online check-in opens and closes, ..."
facts: [check-in-opens, check-in-closes]
---
```

`help-centre/facts.yml` registers every rule with a number that an article states, with the file and pattern where
SkyHub's code enforces it:

```yaml
check-in-opens:
  value: 24
  says: "{value} hours before"
  source: services/booking/src/main/java/com/skyhub/booking/config/CheckInConfig.java
  pattern: 'check-in\.opens-before:PT(\d+)H\}'
```

`tests/test_help_centre_matches_the_code.py` fails when:

- the code no longer says a fact's value (or says it in more than one place);
- an article declares a fact and does not state it in the registered words, or declares one that does not exist;
- **an article contains any number that is not one of the facts it declares**;
- a fact is registered and no article states it.

### Changing a rule or an article

- **A rule changed in the code:** the test names the fact and the file. Update `value` in `facts.yml`, then reread every
  article that lists the fact.
- **A new number in an article:** register it in `facts.yml` with where the code enforces it, and add it to the
  article's `facts`. If no code enforces it, it does not belong in the help centre.
- **A sentence without a number** is not checked by the test. Check it against the code and the ADRs when you write it.

## Run the tests

```bash
cd services/traveller-assistant
python -m venv .venv && source .venv/Scripts/activate     # on macOS/Linux: .venv/bin/activate
pip install -e ".[dev]"
python -m pytest                                          # needs the rest of the repository: it reads the services' code
```
