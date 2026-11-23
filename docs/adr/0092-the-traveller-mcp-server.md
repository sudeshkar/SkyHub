# ADR-0092: The traveller MCP server

**Status:** Accepted
**Date:** 2026-11-23

## Context

ADR-0090 planned the traveller assistant as a crew of agents (CrewAI) that reach SkyHub through an **MCP server**, and ADR-0091 built its first tool, `search_help_centre`. This slice is that server: the one place where the assistant's tools live, so the crew (next slice), the MCP Inspector or any MCP client (Claude Desktop, an IDE) can use them in the same way.

SkyHub already has an MCP server, the disruption agent's (ADR-0022). It logs in with an `OPS` staff account and lists the bookings on a flight. A traveller's questions must never reach a process that holds that login.

Two facts found while building this slice:

- **CrewAI pins the MCP SDK below 2.** CrewAI 1.15.44 needs `mcp>=1.30,<2`; with the 2.x SDK that the disruption agent uses (`mcp>=2.3`), pip falls back to CrewAI 1.6.1, a year older. Both SDKs speak the same protocol on the wire and negotiate its version, so a client of one works with a server of the other.
- **Flight search needs no login.** flight-search permits `GET /api/v1/itineraries` to anyone (the website's server calls it for every visitor), so a traveller tool can search flights without any credential.

## Options considered

Where the traveller tools live:

1. **Add them to the disruption agent's MCP server.** Rejected: one process would hold the staff login and answer travellers. A tool bug or a prompt that talks a model into the wrong tool would be one step from every booking on a flight.
2. **No MCP: the crew calls the Python functions directly.** Simpler, but the author's plan is an MCP server, and it is what makes the tools usable from any MCP client and testable over the real protocol.
3. **A second MCP server, for travellers only, holding no credentials.** Chosen.

Which MCP SDK:

1. **2.x, like the disruption agent.** Rejected: CrewAI would fall back to an old release.
2. **1.30 (`FastMCP`), which current CrewAI accepts.** Chosen. The disruption agent keeps 2.x; the two never share a process.

## Decision

- **`skyhub-traveller`, an MCP server of its own** (`traveller_assistant.server`, `python -m traveller_assistant.server`), speaking MCP over stdin and stdout. It opens no network port: the crew of the next slice starts it as a child process, so there is nothing to reach from outside and nothing to authenticate.
- **It holds no credentials.** It has no staff account, no booking token and no key: its only settings are where flight-search is. A test checks that no request it makes carries an `Authorization` header. Booking tools that act for one traveller, with that traveller's own manage token, are ADR-0090's slice 5.
- **Two tools, both read-only** (marked with MCP's `readOnlyHint`), and a test that the server offers exactly these, so a tool that changes a booking cannot be added by accident:
  - `search_help_centre(question, limit)`: ADR-0091's tool, unchanged.
  - `search_flights(origin, destination, date)`: direct flights and one-stop connections on a day, from flight-search. Airport codes are three letters (any case); the date is `YYYY-MM-DD`; origin and destination must differ. It returns **at most 10** itineraries, earliest first as flight-search orders them, with `total` saying how many there were. Each has its flights (number, airports, UTC times, status, the new departure time of a delayed flight), the connection (where, layover, whether it changes airport and the transfer time) and the fare "from" per passenger. A `note` tells the model that times are UTC, that the fare is a starting price and seats and the final price are shown when booking, and that cancelled flights are never listed.
- **Tools are classes in a registry, as in the disruption agent:** adding a tool is one class and one line, with no change to the server. A call the tool refuses (a bad airport code) or a service that cannot answer (flight-search down, after a 5-second timeout) comes back as `{"error": "..."}` in words a model can act on, never as MCP's generic failure. An error from flight-search's own validation (`400`) passes its `detail` on.
- **The help-centre index is built when the server starts** (ADR-0091): a few seconds, then every call is in memory. Logs and the model's first download go to stderr, because stdout carries the protocol; a test starts the real process over stdio and calls a tool to prove it.

## Consequences

- Positive: the traveller's side and the staff side of SkyHub's AI are two processes with nothing in common: a traveller cannot reach the staff login through a prompt, because it is not there.
- Positive: the tools can be tried in the MCP Inspector or Claude Desktop with no crew and no API key (`README.md` says how).
- Positive: a new read-only tool (say, a flight's status by number) is one class.
- Negative: **two MCP SDK majors in the repository** (2.x for the disruption agent, 1.30 for the traveller server) until CrewAI accepts 2.x. Their Python APIs differ (`MCPServer` and `Client` against `FastMCP` and `ClientSession`), so code is not shared between them.
- Negative: **times are UTC.** flight-search returns UTC and leaves local time to its clients (ADR-0005); the model is told so, but a traveller asking "what time does it leave?" may get a UTC time unless the crew converts it. Follow-up: give the tool each airport's time zone.
- Negative: **no list of destinations.** The model must know that Mumbai is `BOM`; flight-search has no endpoint listing the airports SkyHub flies to. An unknown code returns no flights rather than an error.
- Negative: **free seats are not shown.** A flight with no seats left is still listed; the seat map at booking says so. Counting seats would mean a call to the booking service per flight.
- No metric, alert or runbook entry yet: the server has one client, the crew, which arrives with the API in the next slice (ADR-0090's slice 4); they come with it.
- Follow-up work: the CrewAI crew and `POST /api/v1/assistant/messages`, starting this server over stdio.
