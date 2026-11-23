# Traveller assistant

A chat assistant for SkyHub's travellers: it answers questions about SkyHub's rules from a **help centre**, and later
about the traveller's own booking. It never changes a booking: cancelling, changing a seat and checking in are done by
the traveller on the Manage booking page. Design and the slices it is built in: [ADR-0090](../../docs/adr/0090-traveller-assistant-and-its-help-centre.md).

**Built so far (slices 1 and 2 of 6):** the help centre and the test that keeps it true, and `search_help_centre`, the
tool that finds the passages answering a question, with Chroma ([ADR-0091](../../docs/adr/0091-searching-the-help-centre-with-chroma.md)).
The MCP server, the CrewAI crew, the API and the website chat follow, one slice at a time.

```
help-centre/*.md ──► passages (one per ## section, at most 150 words)
                        │ all-MiniLM-L6-v2, run locally (free, no key)
                        ▼
                     Chroma, in memory, cosine distance, built at start
                        │
search_help_centre("can I get a refund?") ──► the nearest passages within 0.70, each naming its article,
                                              and the rule: answer only from these, or say you do not know
```

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

## Searching the help centre

```python
from traveller_assistant.embedding import MiniLmEmbedder
from traveller_assistant.index import build_index
from traveller_assistant.tools import search_help_centre

index = build_index(MiniLmEmbedder())          # the first time, downloads the model (about 80 MB) to ~/.cache/chroma
search_help_centre(index, "Can I still get my money back if I cancel tomorrow?")
# {"passages": [{"article": "cancellations-and-refunds", "title": ..., "section": "Do I get my money back?", "text": ...}, ...],
#  "note": "Answer only from these passages and name the article you used. ..."}
```

A question that has nothing to do with SkyHub gets no passages. A travel question the help centre does not cover
(baggage, for instance) can still get near passages that do not answer it, which is why the `note` is always there: see
the ADR.

## Run the tests and the evals

The assistant runs on **Python 3.13**: CrewAI, which a later slice adds, does not install on 3.14 yet.

```bash
cd services/traveller-assistant
uv venv --python 3.13 .venv                               # or: py -3.13 -m venv .venv
source .venv/Scripts/activate                             # on macOS/Linux: .venv/bin/activate
pip install -e ".[dev]"                                   # with uv: uv pip install -e ".[dev]"
python -m pytest                                          # tests and evals; needs the rest of the repository
python -m evals                                           # every eval question with its rank and distance
```

`python -m pytest` runs the unit tests (a fake embedder, offline, about a second) and the retrieval evals (the real
model, about 15 seconds). The evals fail if hit@1 falls below 88%, hit@3 below 96%, or any off-topic question gets a
passage. To try another cut-off, `python -m evals --max-distance 0.65`.
