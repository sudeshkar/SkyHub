# Traveller assistant

A chat assistant for SkyHub's travellers: it answers questions about SkyHub's rules from a **help centre**, and later
about the traveller's own booking. It never changes a booking: cancelling, changing a seat and checking in are done by
the traveller on the Manage booking page. Design and the slices it is built in: [ADR-0090](../../docs/adr/0090-traveller-assistant-and-its-help-centre.md).

**Built so far (slices 1 to 3 of 6):** the help centre and the test that keeps it true; `search_help_centre`, the
tool that finds the passages answering a question, with Chroma ([ADR-0091](../../docs/adr/0091-searching-the-help-centre-with-chroma.md));
and **`skyhub-traveller`, the MCP server** that offers it with `search_flights` ([ADR-0092](../../docs/adr/0092-the-traveller-mcp-server.md)).
The CrewAI crew, the API and the website chat follow, one slice at a time.

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

## The MCP server

`skyhub-traveller` offers two read-only tools over MCP (stdin and stdout, no network port). It holds **no credentials**:
no staff account, no booking token, no key. It is a different process from the disruption agent's server, which holds a
staff login (ADR-0092).

| Tool | What it does |
|---|---|
| `search_help_centre(question, limit=3)` | The help-centre passages that answer a question, each naming its article, with the rule "answer only from these, or say you do not know" |
| `search_flights(origin, destination, date)` | Direct flights and connections in Dubai on one day, from flight-search: at most 10, with UTC times, status and the lowest fare per passenger |

A refused call (a bad airport code) or flight-search being down comes back as `{"error": "..."}`, worded for the model.

```bash
python -m traveller_assistant.server                  # SKYHUB_SEARCH_API_URL, default http://localhost:8081
```

**Try it in the MCP Inspector** (needs Node.js; no API key):

```bash
npx @modelcontextprotocol/inspector .venv/Scripts/python -m traveller_assistant.server
```

**Or from Claude Desktop**, in `claude_desktop_config.json` (use the full path to this folder's `.venv` Python):

```json
{ "mcpServers": { "skyhub-traveller": {
    "command": "C:/path/to/skyhub/services/traveller-assistant/.venv/Scripts/python.exe",
    "args": ["-m", "traveller_assistant.server"],
    "env": { "SKYHUB_SEARCH_API_URL": "http://localhost:8081" } } } }
```

The first start downloads the embedding model (about 80 MB); after that a start takes a few seconds.

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

`python -m pytest` runs the unit tests (a fake embedder and a fake flight-search, offline), one test that starts the real
server over stdio, and the retrieval evals (the real model); about 25 seconds in all. The evals fail if hit@1 falls below 88%, hit@3 below 96%, or any off-topic question gets a
passage. To try another cut-off, `python -m evals --max-distance 0.65`.
