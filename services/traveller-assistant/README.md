# Traveller assistant

A chat assistant for SkyHub's travellers: it answers questions about SkyHub's rules from a **help centre**, and later
about the traveller's own booking. It never changes a booking: cancelling, changing a seat and checking in are done by
the traveller on the Manage booking page. Design and the slices it is built in: [ADR-0090](../../docs/adr/0090-traveller-assistant-and-its-help-centre.md).

**Built so far (slices 1 to 5 of 6):** the help centre and the test that keeps it true; `search_help_centre`, the
tool that finds the passages answering a question, with Chroma ([ADR-0091](../../docs/adr/0091-searching-the-help-centre-with-chroma.md));
**`skyhub-traveller`, the MCP server** that offers it with `search_flights` ([ADR-0092](../../docs/adr/0092-the-traveller-mcp-server.md));
**the CrewAI crew behind an API**, with every answer checked in code ([ADR-0093](../../docs/adr/0093-the-traveller-assistant-crew-and-its-api.md));
and **the traveller's own booking**, read with the manage token the website already holds, never through the model and
without names ([ADR-0094](../../docs/adr/0094-the-assistant-reads-the-travellers-own-booking.md)). The chat on the website follows.

```
website's server ──POST /api/v1/assistant/messages──► traveller-assistant (127.0.0.1:8085, shared token)
                                                          │ router (one short model call): help | flights | booking | other
                                                          ▼
                                     CrewAI crew: policy agent (help centre), trip agent (flights + help centre),
                                     or booking agent (the open booking, bound to this message + help centre)
                                                          │ tools, recorded for this message
                                                          ▼
                                     skyhub-traveller MCP server, started once, over stdio ──► Chroma, flight-search
                                                          │
                                     code checks the answer: cited articles and named flights must be what the
                                     tools returned, or the traveller gets the standard "I don't know" reply
```

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

`skyhub-traveller` offers three read-only tools over MCP (stdin and stdout, no network port). It holds **no credentials**:
no staff account and no key, and a traveller's manage token reaches it only with the call that uses it. It is a
different process from the disruption agent's server, which holds a staff login (ADR-0092).

| Tool | What it does |
|---|---|
| `search_help_centre(question, limit=3)` | The help-centre passages that answer a question, each naming its article, with the rule "answer only from these, or say you do not know" |
| `search_flights(origin, destination, date)` | Direct flights and connections in Dubai on one day, from flight-search: at most 10, with UTC times, status and the lowest fare per passenger |
| `get_my_booking(reference, booking_token)` | The booking that manage token opens: status, each flight's times, delay, seats and who is checked in, and the refund. Passengers by position only: never a name, the email, the reference or a barcode. In the crew, the reference and the token are added by code; the model calls it with nothing |

A refused call (a bad airport code) or flight-search being down comes back as `{"error": "..."}`, worded for the model.

```bash
python -m traveller_assistant.server                  # SKYHUB_SEARCH_API_URL (default http://localhost:8081),
                                                      # SKYHUB_BOOKING_API_URL (default http://localhost:8082)
```

**Try it in the MCP Inspector** (needs Node.js; no API key):

```bash
npx @modelcontextprotocol/inspector .venv/Scripts/python -m traveller_assistant.server
```

**Or from a desktop MCP client**, in its `mcpServers` settings (use the full path to this folder's `.venv` Python):

```json
{ "mcpServers": { "skyhub-traveller": {
    "command": "C:/path/to/skyhub/services/traveller-assistant/.venv/Scripts/python.exe",
    "args": ["-m", "traveller_assistant.server"],
    "env": { "SKYHUB_SEARCH_API_URL": "http://localhost:8081" } } } }
```

The first start downloads the embedding model (about 80 MB); after that a start takes a few seconds.

## The API

```bash
export GROQ_API_KEY=...                                   # a free Groq key: https://console.groq.com
export SKYHUB_ASSISTANT_API_TOKEN=$(openssl rand -base64 32)   # shared with the website's server
python -m traveller_assistant.web                         # http://127.0.0.1:8085
```

| Setting | Default | |
|---|---|---|
| `GROQ_API_KEY` | (required) | The model's key |
| `SKYHUB_ASSISTANT_API_TOKEN` | (required, 32+ characters) | Only a caller with it gets an answer |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Rerun the answer evals after changing it |
| `SKYHUB_SEARCH_API_URL` | `http://localhost:8081` | flight-search, passed to the MCP server |
| `SKYHUB_BOOKING_API_URL` | `http://localhost:8082` | The booking service, passed to the MCP server (it gets nothing else: not the key, not the token) |
| `SKYHUB_ASSISTANT_HOST`, `SKYHUB_ASSISTANT_PORT` | `127.0.0.1`, `8085` | |

```bash
curl -s localhost:8085/api/v1/assistant/messages -H "Authorization: Bearer $SKYHUB_ASSISTANT_API_TOKEN" \
     -H 'Content-Type: application/json' -d '{"message": "Can I get a refund if I cancel tomorrow?"}'
# {"reply": "You will receive a full refund only if you cancel at least 24 hours before ...", "route": "help",
#  "outcome": "answered", "sources": [{"article": "cancellations-and-refunds", "title": "Cancellations and refunds"}]}
```

**The traveller's own booking**: the website's server adds the booking the traveller has open on the Manage page, from
its httpOnly cookie, as two headers. The model never sees either, and without them a question about "my booking" gets a
reply saying how to open it.

```bash
curl -s localhost:8085/api/v1/assistant/messages -H "Authorization: Bearer $SKYHUB_ASSISTANT_API_TOKEN" \
     -H "X-Booking-Reference: ABC234" -H "X-Booking-Token: $MANAGE_TOKEN" \
     -H 'Content-Type: application/json' -d '{"message": "Am I checked in for my first flight?"}'
```

The contract is [`api-specs/traveller-assistant-api.yaml`](../../api-specs/traveller-assistant-api.yaml). The service keeps
no conversation: the caller sends up to 6 earlier turns. At most 2 answers run at once; a third caller gets a `503`
after 2 seconds, and so does a model that is down or out of its rate limit (with `Retry-After`). `GET /metrics` serves
`skyhub_assistant_messages_total{route,outcome}` and `skyhub_assistant_message_seconds`.

**On Groq's free tier, expect two or three messages a minute**: an answer takes about 1,300 to 4,600 tokens.

## Run the tests and the evals

The assistant runs on **Python 3.13**: CrewAI, which a later slice adds, does not install on 3.14 yet.

```bash
cd services/traveller-assistant
uv venv --python 3.13 .venv                               # or: py -3.13 -m venv .venv
source .venv/Scripts/activate                             # on macOS/Linux: .venv/bin/activate
pip install -e ".[dev]"                                   # with uv: uv pip install -e ".[dev]"
python -m pytest                                          # tests and evals; needs the rest of the repository
python -m evals                                           # retrieval: every question with its rank and distance
python -m evals.answers                                   # the whole assistant with the real model (needs GROQ_API_KEY)
SKYHUB_LIVE_EVALS=1 python -m pytest evals/test_answers.py   # the same, held to its bars
```

`python -m pytest` runs the unit tests (a fake embedder, a fake flight-search, and a scripted model that CrewAI runs
for real), one test that starts the real MCP server over stdio, and the retrieval evals (the real embedding model); no
key is needed. The **answer evals** use Groq and spend about 50,000 of the free tier's tokens over about four minutes, so
they run only when asked: `python -m evals.answers` reads `GROQ_API_KEY` from the environment or the repository's
git-ignored `.env` (it never prints it), and starts a stand-in flight-search on this machine. The evals fail if hit@1 falls below 88%, hit@3 below 96%, or any off-topic question gets a
passage. To try another cut-off, `python -m evals --max-distance 0.65`.
