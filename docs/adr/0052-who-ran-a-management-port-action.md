# ADR-0052: Who ran a management-port action

**Status:** Accepted

## Context

The notification service's dead-letter tools (replay, ADR-0032; skip and take-off, ADR-0037) change what is sent to travellers, but the management port has no login: its protection is that it is never exposed publicly (ADR-0010). ADR-0037 said so plainly: "there is no record of who skipped it". When a message that should have been sent is left out, nobody can say who decided that.

## Options considered

1. **Authenticate the port** (a login, a proxy that adds an identity, client certificates). The right answer for a shared operations tool, and a deployment decision that belongs with the gateway work, not with this service.
2. **Do nothing, rely on access to the port.** Leaves the gap ADR-0037 named.
3. **Ask the person to say who they are, record it, and say plainly that it is their word.** Costs a field; gives a trail now, and the field is the same one a real identity would later fill.

## Decision

- **Option 3.** `replay`, `skip` and `unskip` each require an `operator` (request body for `POST`, query parameter for `DELETE`). Without one the call is a 400 and nothing happens. The read operations (listing) need none.
- **A plain name only:** 1 to 60 letters, digits, spaces and `. _ - @ +`, trimmed. Anything else is a 400, so a name can never forge or split a log line, whatever tool reads the logs.
- **Where it is recorded:** a skip stores `skippedBy` with the reason and the time and shows it in the skip listing; replay, skip and take-off each write a log line with `ops.operator`. Skips made before this change show `unknown (before ADR-0052)` rather than a guessed name.
- **It is self-declared and is recorded as such.** Nothing checks it. It records who *said* they did it, which is enough to ask them why, and it is not evidence against someone who lied. Nobody should read it as access control.
- It is one small value (`Operator`) at the edge, so a real identity from option 1 can replace how it is obtained without touching the replay or skip rules.

## Consequences

- Positive: every decision to leave a message out, or to put messages back, has a name to ask.
- Positive: the data is already in the right place for option 1 to fill later.
- Negative: **the runbook commands and any script calling these endpoints must now send an `operator`** (the live-check failure path was updated). A call without one fails loudly, which is intended.
- Negative: **not authentication.** Anyone who can reach the port can still type any name. The port's reachability remains the only control.
- Negative: taking a skip off, and replays, are recorded in logs only, not in a table; only the skip itself is stored.
