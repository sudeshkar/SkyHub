# Disruption agent

Reads a flight disruption, finds the bookings it affects, and **proposes** where each could go instead. It never changes a
booking: a person approves. Design and limits: [ADR-0022](../../docs/adr/0022-disruption-agent-proposes-code-verifies.md).

```
                         ┌──────────────── MCP server (own process, holds the staff login) ───────────────┐
 python -m disruption_agent│  get_disruption_impact   ──►  booking  GET /api/v1/ops/flights/…/bookings      │
   SK501 2026-11-02        │  find_alternatives       ──►  flight-search itineraries + booking seat maps    │
        │                  └────────────────────────────────────────────────────────────────────────────────┘
        ▼
   agent: pages the bookings itself ─► batches of 40 ─► Groq model (sees tool results only) ─► proposals
                                                                                          │
                                       guardrails (rules + seat ledger) check every proposal ◄┘
                                                                                          │
                                       report: REBOOK / NEEDS_HUMAN per booking, plus what was refused
```

## Run it

```bash
cd services/disruption-agent
python -m venv .venv && source .venv/Scripts/activate     # on macOS/Linux: .venv/bin/activate
pip install -e ".[dev]"
python -m pytest                                          # 86 tests: units, guardrails, evals, a fuzz test. No key needed.
python -m evals                                           # the baseline planner on four scenarios, with a safety check
```

To run it on a real disruption you need the services running (see the root README), an `OPS` staff account for the agent
(an administrator creates it: `POST /api/v1/ops/staff`), and a free Groq key:

```bash
export SKYHUB_AGENT_USERNAME=agent.ops
export SKYHUB_AGENT_PASSWORD=...            # 12+ characters
export GROQ_API_KEY=...                     # https://console.groq.com
python -m disruption_agent SK001 2027-01-01          # prints proposals; add --json for machines
python -m disruption_agent SK001 2027-01-01 --apply  # then asks about each move: y / n / a (all the rest) / q
python -m evals --live                               # judge the Groq model against the baseline
```

Settings (all environment variables): `SKYHUB_BOOKING_API_URL` (default `http://localhost:8082`),
`SKYHUB_SEARCH_API_URL` (`http://localhost:8081`), `GROQ_MODEL` (`llama-3.3-70b-versatile`).

`--apply` carries out only what you approve, one booking at a time, through the booking service, which re-checks every rule
(ADR-0023). There is no unattended mode: it needs a terminal. Moving is deliberately not an MCP tool.

The MCP server can also run alone, for any MCP client: `python -m disruption_agent.server`.

## Layout

| File | Job |
|---|---|
| `models.py` | The vocabulary: immutable, validated values (`Leg`, `AffectedBooking`, `Proposal`, `Report`) |
| `clients.py` | `BookingClient` (staff login, token reuse) and `SearchClient` (remembers every answer) |
| `cache.py` | `TtlCache`: LRU with an expiry time and a size limit, thread-safe, O(1) |
| `planning.py` | `ImpactAnalyzer` (who, and each booking's window) and `AlternativeFinder` (which flights) |
| `tools.py` | Tools as classes, a registry that installs them, errors returned as readable answers |
| `server.py`, `toolbox.py` | The MCP server, and the adapter the agent reaches tools through |
| `guardrails.py` | `Rule` classes, the `SeatLedger`, `GuardrailPipeline`, `Policy` |
| `agent.py`, `llm.py` | The loop, and the `ChatModel` port with the Groq adapter |
| `apply.py` | `Applier` and the `Approver` port (console prompt): moves only what a person approves |
| `evals/` | Baseline planner, independent oracle, chaos model, scenarios, runner |

Adding a safety check: write a `Rule`, add it to `default_rules()`. Adding a tool: write a `Tool`, add it to the registry in
`server.py`. Trying another model: write a `ChatModel` adapter. None of these edits an existing class.
